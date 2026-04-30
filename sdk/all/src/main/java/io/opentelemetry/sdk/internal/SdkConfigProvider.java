/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.internal;

import static java.util.Objects.requireNonNull;

import io.opentelemetry.api.incubator.config.ConfigChangeListener;
import io.opentelemetry.api.incubator.config.ConfigChangeRegistration;
import io.opentelemetry.api.incubator.config.ConfigProvider;
import io.opentelemetry.api.incubator.config.DeclarativeConfigException;
import io.opentelemetry.api.incubator.config.DeclarativeConfigProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SDK implementation of {@link ConfigProvider}.
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
public final class SdkConfigProvider implements ConfigProvider {
  private static final Logger logger = Logger.getLogger(SdkConfigProvider.class.getName());
  private static final ConfigChangeRegistration NOOP_CHANGE_REGISTRATION = () -> {};

  private final AtomicReference<DeclarativeConfigProperties> openTelemetryConfigModel;
  private final ConcurrentMap<String, CopyOnWriteArrayList<ListenerRegistration>> listenersByPath =
      new ConcurrentHashMap<>();
  private final AtomicBoolean isShutdown = new AtomicBoolean(false);

  private SdkConfigProvider(DeclarativeConfigProperties openTelemetryConfigModel) {
    this.openTelemetryConfigModel = new AtomicReference<>(requireNonNull(openTelemetryConfigModel));
  }

  /**
   * Create a {@link SdkConfigProvider}.
   *
   * @param openTelemetryConfigModel {@link DeclarativeConfigProperties} corresponding to the {@code
   *     OpenTelemetryConfiguration} type, i.e. the root node.
   * @return the {@link SdkConfigProvider} instance
   */
  public static SdkConfigProvider create(DeclarativeConfigProperties openTelemetryConfigModel) {
    return new SdkConfigProvider(openTelemetryConfigModel);
  }

  @Override
  public DeclarativeConfigProperties getInstrumentationConfig() {
    return requireNonNull(openTelemetryConfigModel.get()).get("instrumentation/development");
  }

  @Override
  public ConfigChangeRegistration addConfigChangeListener(
      String path, ConfigChangeListener listener) {
    requireNonNull(listener, "listener");
    String watchedPath = normalizeAndValidatePath(path); // fail fast on invalid path
    if (isShutdown.get()) {
      return NOOP_CHANGE_REGISTRATION;
    }

    ListenerRegistration registration = new ListenerRegistration(watchedPath, listener);
    listenersByPath
        .computeIfAbsent(watchedPath, unused -> new CopyOnWriteArrayList<>())
        .add(registration);
    if (isShutdown.get()) {
      registration.close();
      return NOOP_CHANGE_REGISTRATION;
    }
    return registration;
  }

  @Override
  public void updateConfig(String path, DeclarativeConfigProperties newSubtree) {
    requireNonNull(newSubtree, "newSubtree");
    String normalizedPath = normalizeAndValidatePath(path);
    if (isShutdown.get()) {
      return;
    }
    Map<String, Object> subtreeMap = DeclarativeConfigProperties.toMap(newSubtree);
    while (true) {
      DeclarativeConfigProperties current = requireNonNull(openTelemetryConfigModel.get());
      Map<String, Object> rootMap = DeclarativeConfigProperties.toMap(current);
      Map<String, Object> newRootMap = withSubtreeAtPath(rootMap, normalizedPath, subtreeMap);
      DeclarativeConfigProperties newRoot =
          YamlDeclarativeConfigProperties.create(newRootMap, current.getComponentLoader());
      if (openTelemetryConfigModel.compareAndSet(current, newRoot)) {
        notifyListeners(current, newRoot);
        return;
      }
    }
  }

  @Override
  public void setConfigProperty(String path, String key, Object value) {
    requireNonNull(key, "key");
    requireNonNull(value, "value");
    String normalizedPath = normalizeAndValidatePath(path);
    if (isShutdown.get()) {
      return;
    }
    while (true) {
      DeclarativeConfigProperties current = requireNonNull(openTelemetryConfigModel.get());
      Map<String, Object> rootMap = DeclarativeConfigProperties.toMap(current);
      Map<String, Object> newRootMap = withPropertyAtPath(rootMap, normalizedPath, key, value);
      DeclarativeConfigProperties newRoot =
          YamlDeclarativeConfigProperties.create(newRootMap, current.getComponentLoader());
      if (openTelemetryConfigModel.compareAndSet(current, newRoot)) {
        notifyListeners(current, newRoot);
        return;
      }
    }
  }

  // Visible for testing.
  void updateOpenTelemetryConfigModel(DeclarativeConfigProperties updatedOpenTelemetryConfigModel) {
    requireNonNull(updatedOpenTelemetryConfigModel, "updatedOpenTelemetryConfigModel");
    DeclarativeConfigProperties previous =
        openTelemetryConfigModel.getAndSet(updatedOpenTelemetryConfigModel);
    notifyListeners(previous, updatedOpenTelemetryConfigModel);
  }

  private void notifyListeners(
      DeclarativeConfigProperties previous, DeclarativeConfigProperties updated) {
    if (isShutdown.get()) {
      return;
    }

    for (Map.Entry<String, CopyOnWriteArrayList<ListenerRegistration>> entry :
        listenersByPath.entrySet()) {
      String watchedPath = entry.getKey();
      DeclarativeConfigProperties previousConfigAtPath = resolvePath(previous, watchedPath);
      DeclarativeConfigProperties updatedConfigAtPath = resolvePath(updated, watchedPath);
      if (hasSameContents(previousConfigAtPath, updatedConfigAtPath)) {
        continue;
      }

      for (ListenerRegistration registration : entry.getValue()) {
        registration.notifyChange(watchedPath, updatedConfigAtPath);
      }
    }
  }

  void shutdown() {
    if (!isShutdown.compareAndSet(false, true)) {
      return;
    }
    for (List<ListenerRegistration> registrations : listenersByPath.values()) {
      for (ListenerRegistration registration : registrations) {
        registration.close();
      }
    }
    listenersByPath.clear();
  }

  /**
   * Returns a new map with the subtree at {@code normalizedPath} replaced by {@code subtreeMap}.
   * Intermediate maps along the path are copied, not mutated.
   */
  private static Map<String, Object> withSubtreeAtPath(
      Map<String, Object> rootMap, String normalizedPath, Map<String, Object> subtreeMap) {
    String relativePath = normalizedPath.substring(1);
    if (relativePath.isEmpty()) {
      return new HashMap<>(subtreeMap);
    }
    String[] segments = relativePath.split("\\.");
    return copyAndReplace(rootMap, segments, 0, normalizedPath, subtreeMap);
  }

  /**
   * Returns a new map with the property {@code key}={@code value} set within the map at {@code
   * normalizedPath}. Intermediate maps along the path are copied, not mutated.
   */
  private static Map<String, Object> withPropertyAtPath(
      Map<String, Object> rootMap, String normalizedPath, String key, Object value) {
    String relativePath = normalizedPath.substring(1);
    if (relativePath.isEmpty()) {
      Map<String, Object> copy = new HashMap<>(rootMap);
      copy.put(key, value);
      return copy;
    }
    String[] segments = relativePath.split("\\.");
    Map<String, Object> leafMap = resolveToMap(rootMap, segments, normalizedPath);
    Map<String, Object> updatedLeaf = new HashMap<>(leafMap);
    updatedLeaf.put(key, value);
    return copyAndReplace(rootMap, segments, 0, normalizedPath, updatedLeaf);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> copyAndReplace(
      Map<String, Object> current,
      String[] segments,
      int depth,
      String normalizedPath,
      Map<String, Object> replacement) {
    Map<String, Object> copy = new HashMap<>(current);
    String segment = segments[depth];
    if (depth == segments.length - 1) {
      copy.put(segment, replacement);
      return copy;
    }
    Object child = current.get(segment);
    Map<String, Object> childMap;
    if (child instanceof Map) {
      childMap = (Map<String, Object>) child;
    } else if (child == null) {
      childMap = new HashMap<>();
    } else {
      throw schemaConflict(normalizedPath, segment, child);
    }
    copy.put(segment, copyAndReplace(childMap, segments, depth + 1, normalizedPath, replacement));
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolveToMap(
      Map<String, Object> rootMap, String[] segments, String normalizedPath) {
    Map<String, Object> current = rootMap;
    for (String segment : segments) {
      Object child = current.get(segment);
      if (child instanceof Map) {
        current = (Map<String, Object>) child;
      } else if (child == null) {
        return new HashMap<>();
      } else {
        throw schemaConflict(normalizedPath, segment, child);
      }
    }
    return current;
  }

  private static DeclarativeConfigException schemaConflict(
      String normalizedPath, String segment, Object actual) {
    return new DeclarativeConfigException(
        "Cannot traverse path '"
            + normalizedPath
            + "': segment '"
            + segment
            + "' resolves to a "
            + actual.getClass().getSimpleName()
            + ", not a mapping");
  }

  // TODO: optimize later, this is an expensive operation.
  // But note that we only do this on a mutation, and these are expected to be infrquent
  // so maybe acceptable
  private static boolean hasSameContents(
      DeclarativeConfigProperties left, DeclarativeConfigProperties right) {
    return DeclarativeConfigProperties.toMap(left).equals(DeclarativeConfigProperties.toMap(right));
  }

  private static DeclarativeConfigProperties resolvePath(
      DeclarativeConfigProperties root, String watchedPath) {
    String relativePath = watchedPath.substring(1);
    if (relativePath.isEmpty()) {
      return root;
    }

    DeclarativeConfigProperties current = root;
    String[] segments = relativePath.split("\\.");
    for (String segment : segments) {
      if (segment.isEmpty()) {
        return DeclarativeConfigProperties.empty();
      }
      current = current.get(segment);
    }
    return current;
  }

  private static String normalizeAndValidatePath(String path) {
    String watchedPath = requireNonNull(path, "path").trim();
    if (!watchedPath.startsWith(".")) {
      throw new IllegalArgumentException(
          "Config change listener path must be absolute and start with '.': " + path);
    }
    if (watchedPath.indexOf('*') >= 0) {
      throw new IllegalArgumentException(
          "Config change listener path does not support wildcards: " + path);
    }
    if (watchedPath.indexOf('[') >= 0 || watchedPath.indexOf(']') >= 0) {
      throw new IllegalArgumentException(
          "Config change listener path does not support sequence indexing: " + path);
    }
    return watchedPath;
  }

  private final class ListenerRegistration implements ConfigChangeRegistration {
    private final String watchedPath;
    private final ConfigChangeListener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ListenerRegistration(String watchedPath, ConfigChangeListener listener) {
      this.watchedPath = watchedPath;
      this.listener = listener;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      CopyOnWriteArrayList<ListenerRegistration> registrations = listenersByPath.get(watchedPath);
      if (registrations == null) {
        return;
      }
      registrations.remove(this);
      if (registrations.isEmpty()) {
        listenersByPath.remove(watchedPath, registrations);
      }
    }

    private void notifyChange(String changedPath, DeclarativeConfigProperties updatedConfigAtPath) {
      if (closed.get()) {
        return;
      }
      try {
        listener.onChange(changedPath, updatedConfigAtPath);
      } catch (Throwable throwable) {
        logger.log(
            Level.WARNING,
            "Config change listener threw while handling path " + changedPath,
            throwable);
      }
    }
  }

  @Override
  public String toString() {
    return "SdkConfigProvider{" + "instrumentationConfig=" + getInstrumentationConfig() + '}';
  }
}
