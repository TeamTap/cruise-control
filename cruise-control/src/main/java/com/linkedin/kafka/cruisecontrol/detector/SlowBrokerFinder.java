/*
 * Copyright 2019 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.detector;

import com.linkedin.cruisecontrol.common.config.ConfigException;
import com.linkedin.cruisecontrol.detector.metricanomaly.MetricAnomaly;
import com.linkedin.cruisecontrol.detector.metricanomaly.MetricAnomalyFinder;
import com.linkedin.cruisecontrol.monitor.sampling.aggregator.AggregatedMetricValues;
import com.linkedin.cruisecontrol.monitor.sampling.aggregator.ValuesAndExtrapolations;
import com.linkedin.kafka.cruisecontrol.KafkaCruiseControl;
import com.linkedin.kafka.cruisecontrol.config.constants.AnomalyDetectorConfig;
import com.linkedin.kafka.cruisecontrol.monitor.metricdefinition.KafkaMetricDef;
import com.linkedin.kafka.cruisecontrol.monitor.sampling.holder.BrokerEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linkedin.cruisecontrol.detector.metricanomaly.PercentileMetricAnomalyFinderUtils.isDataSufficient;
import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.toDateString;
import static com.linkedin.kafka.cruisecontrol.detector.AnomalyDetectorUtils.KAFKA_CRUISE_CONTROL_OBJECT_CONFIG;
import static com.linkedin.kafka.cruisecontrol.detector.AnomalyDetectorUtils.ANOMALY_DETECTION_TIME_MS_OBJECT_CONFIG;
import static com.linkedin.kafka.cruisecontrol.detector.MetricAnomalyDetector.METRIC_ANOMALY_DESCRIPTION_OBJECT_CONFIG;
import static com.linkedin.kafka.cruisecontrol.detector.MetricAnomalyDetector.METRIC_ANOMALY_BROKER_ENTITIES_OBJECT_CONFIG;
import static com.linkedin.kafka.cruisecontrol.detector.MetricAnomalyDetector.METRIC_ANOMALY_FIXABLE_OBJECT_CONFIG;


/**
 * This class will check whether there is broker performance degradation (i.e. slow broker) from collected broker metrics.
 *
 * Slow brokers are identified by checking two metrics for each broker. One is the raw metric {@code BROKER_LOG_FLUSH_TIME_MS_999TH}
 * and the other one is the derived metric {@code BROKER_LOG_FLUSH_TIME_MS_999TH / (LEADER_BYTES_IN + REPLICATION_BYTES_IN_RATE).
 * For each metric, the detection is performed in two ways.
 * <ul>
 *   <li>Comparing the latest metric value against broker's own history. If the latest value is larger than
 *       {@link #_metricHistoryMargin} * ({@link #_metricHistoryPercentile} of historical values), it is
 *       considered to be abnormally high.</li>
 *   <li>Comparing the latest metric value against the latest metric value of all active brokers in cluster (i.e. brokers
 *       which serve non-negligible traffic). If the value is larger than {@link #_peerMetricMargin} * ({@link #_peerMetricPercentile}
 *       of all metric values), it is considered to be abnormally high.</li>
 * </ul>
 *
 * If for both metric, certain broker's values are abnormally high, the broker is marked as a slow broker suspect by the finder.
 * Then if this suspect broker's derived metric anomaly persists for some time, it is confirmed to be a slow broker and
 * the finder will report {@link SlowBrokers}} anomaly with broker demotion as self-healing proposal. If the metric
 * anomaly still persists for an extended time, the finder will eventually report {@link SlowBrokers}} anomaly with broker
 * removal as self-healing proposal.
 *
 * The time to report slow broker for demotion and removal is controlled by an internal broker scoring system.
 * The system keeps a "slowness score" for brokers which have metric anomaly detected recently. The scores are updated in
 * each round of detection with following rules.
 * <ul>
 *   <li> For any broker not in the scoring system, once there is metric anomaly detected on it, the broker is added to the system
 *        with the initial "slowness score" of one. </li>
 *   <li> For any broker in the scoring system, if there is metric anomaly detected on it, its "slowness score" increases
 *        by 1. Once the score exceeds {@link #_slowBrokerDemotionScore}, finder begins to report the broker as slow broker
 *        with broker demotion as self-healing proposal; once the score reaches {@link #_slowBrokerDecommissionScore},
 *        finder begin to report the broker as slow broker with broker removal as self-healing proposal (if
 *        {@link #SELF_HEALING_SLOW_BROKER_REMOVAL_ENABLED_CONFIG is configed to be true}).</li>
 *   <li> For any broker in the scoring system, if there is no metric anomaly detected on it, its "slowness score" decreases by 1.
 *        Once "slowness score" reaches zero, the broker is dropped from scoring system.</li>
 * </ul>
 *
 * Note: if there are too many brokers being confirmed as slow broker in the same run, the finder will report the {@link SlowBrokers}
 * anomaly as unfixable. Because this often indicates some serious issue in the cluster and probably requires administrator's
 * intervention to decide the right remediation strategy.
 *
 * Related configurations for this class.
 * <ul>
 *   <li>{@link #SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD_CONFIG}: the bytes in rate threshold in unit of bytes per second to
 *   determine whether to include broker in slow broker detection. If the broker only serves negligible traffic, its derived metric
 *   wil be abnormally high since bytes in rate is used as divisor in metric calculation. Default value is set to
 *   {@link #DEFAULT_SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD}.</li>
 *   <li>{@link #SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD_CONFIG}: the percentile threshold used to compare latest metric value against
 *   historical value in slow broker detection. Default value is set to {@link #DEFAULT_SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD}.</li>
 *   <li>{@link #SLOW_BROKER_METRIC_HISTORY_MARGIN_CONFIG}: the margin used to compare latest metric value against historical value in
 *   slow broker detection. Default value is set to {@link #DEFAULT_SLOW_BROKER_METRIC_HISTORY_MARGIN}.</li>
 *   <li>{@link #SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD_CONFIG}: the percentile threshold used to compare last metric value against
 *   peers' latest value in slow broker detection. Default value is set to {@link #DEFAULT_SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD}.</li>
 *   <li>{@link #SLOW_BROKER_PEER_METRIC_MARGIN_CONFIG}: the margin used to compare last metric value against peers' latest value
 *   in slow broker detection. Default value is set to {@link #DEFAULT_SLOW_BROKER_PEER_METRIC_MARGIN}.</li>
 *   <li>{@link #SLOW_BROKER_DEMOTION_SCORE_CONFIG}: the score threshold to trigger a demotion for slow broker. Default value is set to
 *   {@link #DEFAULT_SLOW_BROKER_DEMOTION_SCORE}.</li>
 *   <li>{@link #SLOW_BROKER_DECOMMISSION_SCORE_CONFIG}: the score threshold to trigger a removal for slow broker. Default value is set to
 *   {@link #DEFAULT_SLOW_BROKER_DECOMMISSION_SCORE}.</li>
 *   <li>{@link #SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO_CONFIG}: the maximum ratio of slow broker in the cluster to trigger self-healing
 *   operation. Default value is set to {@link #DEFAULT_SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO}.</li>
 * </ul>
 */
public class SlowBrokerFinder implements MetricAnomalyFinder<BrokerEntity> {
  private static final Logger LOG = LoggerFactory.getLogger(SlowBrokerFinder.class);
  // The config to enable finder reporting slow broker anomaly with broker removal as self-healing proposal.
  public static final String SELF_HEALING_SLOW_BROKER_REMOVAL_ENABLED_CONFIG = "self.healing.slow.broker.removal.enabled";
  // The config finder uses to indicate anomaly to perform broker demotion or broker removal for self-healing.
  public static final String REMOVE_SLOW_BROKER_CONFIG = "remove.slow.broker";
  public static final String SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD_CONFIG = "slow.broker.bytes.in.rate.detection.threshold";
  public static final double DEFAULT_SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD = 1024.0 * 1024.0;
  public static final String SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD_CONFIG = "slow.broker.metric.history.percentile.threshold";
  public static final double DEFAULT_SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD = 90.0;
  public static final String SLOW_BROKER_METRIC_HISTORY_MARGIN_CONFIG = "slow.broker.metric.history.margin";
  public static final double DEFAULT_SLOW_BROKER_METRIC_HISTORY_MARGIN = 3.0;
  public static final String SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD_CONFIG = "slow.broker.peer.metric.percentile.threshold";
  public static final double DEFAULT_SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD = 50.0;
  public static final String SLOW_BROKER_PEER_METRIC_MARGIN_CONFIG = "slow.broker.peer.metric.margin";
  public static final double DEFAULT_SLOW_BROKER_PEER_METRIC_MARGIN = 10.0;
  public static final String SLOW_BROKER_DEMOTION_SCORE_CONFIG = "slow.broker.demotion.score";
  public static final int DEFAULT_SLOW_BROKER_DEMOTION_SCORE = 5;
  public static final String SLOW_BROKER_DECOMMISSION_SCORE_CONFIG = "slow.broker.decommission.score";
  public static final int DEFAULT_SLOW_BROKER_DECOMMISSION_SCORE = 50;
  public static final String SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO_CONFIG = "slow.broker.self.healing.unfixable.ratio";
  private static final double DEFAULT_SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO = 0.1;
  private static final short BROKER_LOG_FLUSH_TIME_MS_999TH_ID =
      KafkaMetricDef.brokerMetricDef().metricInfo(KafkaMetricDef.BROKER_LOG_FLUSH_TIME_MS_999TH.name()).id();
  private static final short LEADER_BYTES_IN_ID =
      KafkaMetricDef.brokerMetricDef().metricInfo(KafkaMetricDef.LEADER_BYTES_IN.name()).id();
  private static final short REPLICATION_BYTES_IN_RATE_ID =
      KafkaMetricDef.brokerMetricDef().metricInfo(KafkaMetricDef.REPLICATION_BYTES_IN_RATE.name()).id();
  private KafkaCruiseControl _kafkaCruiseControl;
  private boolean _slowBrokerRemovalEnabled;
  private final Map<BrokerEntity, Integer> _brokerSlownessScore;
  private final Map<BrokerEntity, Long> _detectedSlowBrokers;
  private final Percentile _percentile;
  private double _bytesInRateDetectionThreshold;
  private double _metricHistoryPercentile;
  private double _metricHistoryMargin;
  private double _peerMetricPercentile;
  private double _peerMetricMargin;
  private int _slowBrokerDemotionScore;
  private int _slowBrokerDecommissionScore;
  private double _selfHealingUnfixableRatio;

  public SlowBrokerFinder() {
    _brokerSlownessScore = new HashMap<>();
    _detectedSlowBrokers = new HashMap<>();
    _percentile = new Percentile();
  }

  private Set<BrokerEntity> detectMetricAnomalies(Map<BrokerEntity, ValuesAndExtrapolations> metricsHistoryByBroker,
                                                  Map<BrokerEntity, ValuesAndExtrapolations> currentMetricsByBroker) {
    // Preprocess raw metrics to get the metrics of interest for each broker.
    Map<BrokerEntity, List<Double>> historicalLogFlushTimeMetricValues = new HashMap<>();
    Map<BrokerEntity, Double> currentLogFlushTimeMetricValues = new HashMap<>();
    Map<BrokerEntity, List<Double>> historicalPerByteLogFlushTimeMetricValues = new HashMap<>();
    Map<BrokerEntity, Double> currentPerByteLogFlushTimeMetricValues = new HashMap<>();
    Set<Integer> skippedBrokers = new HashSet<>();
    for (BrokerEntity broker : currentMetricsByBroker.keySet()) {
      if (!brokerHasNegligibleTraffic(broker, currentMetricsByBroker)) {
        collectLogFlushTimeMetric(broker,
                                  metricsHistoryByBroker,
                                  currentMetricsByBroker,
                                  historicalLogFlushTimeMetricValues,
                                  currentLogFlushTimeMetricValues);
        collectPerByteLogFlushTimeMetric(broker,
                                         metricsHistoryByBroker,
                                         currentMetricsByBroker,
                                         historicalPerByteLogFlushTimeMetricValues,
                                         currentPerByteLogFlushTimeMetricValues);
      } else {
        skippedBrokers.add(broker.brokerId());
      }
    }

    if (!skippedBrokers.isEmpty()) {
      LOG.info("Skip broker slowness checking for brokers {} because they serve negligible traffic.", skippedBrokers);
    }

    Set<BrokerEntity> detectMetricAnomalies = getMetricAnomalies(historicalLogFlushTimeMetricValues, currentLogFlushTimeMetricValues);
    detectMetricAnomalies.retainAll(getMetricAnomalies(historicalPerByteLogFlushTimeMetricValues, currentPerByteLogFlushTimeMetricValues));
    return detectMetricAnomalies;
  }

  /**
   * Whether broker is currently serving negligible traffic or not.
   * @param broker The broker to check.
   * @param currentMetricsByBroker The subject broker's latest metrics.
   * @return True if broker's current traffic is negligible.
   */
  private boolean brokerHasNegligibleTraffic(BrokerEntity broker,
                                             Map<BrokerEntity, ValuesAndExtrapolations> currentMetricsByBroker) {
    AggregatedMetricValues aggregatedMetricValues = currentMetricsByBroker.get(broker).metricValues();
    double latestTotalBytesIn = aggregatedMetricValues.valuesFor(LEADER_BYTES_IN_ID).latest() +
                                aggregatedMetricValues.valuesFor(REPLICATION_BYTES_IN_RATE_ID).latest();
    return latestTotalBytesIn < _bytesInRateDetectionThreshold;
  }

  private void collectLogFlushTimeMetric(BrokerEntity broker,
                                         Map<BrokerEntity, ValuesAndExtrapolations> metricsHistoryByBroker,
                                         Map<BrokerEntity, ValuesAndExtrapolations> currentMetricsByBroker,
                                         Map<BrokerEntity, List<Double>> historicalLogFlushTimeMetricValues,
                                         Map<BrokerEntity, Double> currentLogFlushTimeMetricValues) {
    AggregatedMetricValues aggregatedMetricValues = currentMetricsByBroker.get(broker).metricValues();
    double latestLogFlushTime = aggregatedMetricValues.valuesFor(BROKER_LOG_FLUSH_TIME_MS_999TH_ID).latest();
    currentLogFlushTimeMetricValues.put(broker, latestLogFlushTime);
    aggregatedMetricValues = metricsHistoryByBroker.get(broker).metricValues();
    double[] historicalLogFlushTime = aggregatedMetricValues.valuesFor(BROKER_LOG_FLUSH_TIME_MS_999TH_ID).doubleArray();
    List<Double> historicalValue = new ArrayList<>(historicalLogFlushTime.length);
    for (int i = 0; i < historicalLogFlushTime.length; i++) {
      if (historicalLogFlushTime[i] > 5.0) {
        historicalValue.add(historicalLogFlushTime[i]);
      }
    }
    historicalLogFlushTimeMetricValues.put(broker, historicalValue);
  }

  private void collectPerByteLogFlushTimeMetric(BrokerEntity broker,
                                                Map<BrokerEntity, ValuesAndExtrapolations> metricsHistoryByBroker,
                                                Map<BrokerEntity, ValuesAndExtrapolations> currentMetricsByBroker,
                                                Map<BrokerEntity, List<Double>> historicalPerByteLogFlushTimeMetricValues,
                                                Map<BrokerEntity, Double> currentPerByteLogFlushTimeMetricValues) {
    AggregatedMetricValues aggregatedMetricValues = currentMetricsByBroker.get(broker).metricValues();
    double latestLogFlushTime = aggregatedMetricValues.valuesFor(BROKER_LOG_FLUSH_TIME_MS_999TH_ID).latest();
    double latestTotalBytesIn = aggregatedMetricValues.valuesFor(LEADER_BYTES_IN_ID).latest() +
                                aggregatedMetricValues.valuesFor(REPLICATION_BYTES_IN_RATE_ID).latest();
    currentPerByteLogFlushTimeMetricValues.put(broker, latestLogFlushTime / latestTotalBytesIn);
    aggregatedMetricValues = metricsHistoryByBroker.get(broker).metricValues();
    double[] historicalBytesIn = aggregatedMetricValues.valuesFor(LEADER_BYTES_IN_ID).doubleArray();
    double[] historicalReplicationBytesIn = aggregatedMetricValues.valuesFor(REPLICATION_BYTES_IN_RATE_ID).doubleArray();
    double[] historicalLogFlushTime = aggregatedMetricValues.valuesFor(BROKER_LOG_FLUSH_TIME_MS_999TH_ID).doubleArray();
    List<Double> historicalValue = new ArrayList<>(historicalBytesIn.length);
    for (int i = 0; i < historicalBytesIn.length; i++) {
      double totalBytesIn = historicalBytesIn[i] + historicalReplicationBytesIn[i];
      if (totalBytesIn >= _bytesInRateDetectionThreshold) {
        historicalValue.add(historicalLogFlushTime[i] / totalBytesIn);
      }
    }
    historicalPerByteLogFlushTimeMetricValues.put(broker, historicalValue);
  }

  private Set<BrokerEntity> getMetricAnomalies(Map<BrokerEntity, List<Double>> historicalValueByBroker,
                                               Map<BrokerEntity, Double> currentValueByBroker) {
    Set<BrokerEntity> detectedMetricAnomalies = new HashSet<>();
    // Detect metric anomalies by comparing each broker's current metric value against historical value.
    detectMetricAnomaliesFromHistory(historicalValueByBroker, currentValueByBroker, detectedMetricAnomalies);
    // Detect metric anomalies by comparing each broker's metric value against its peers' value.
    detectMetricAnomaliesFromPeers(currentValueByBroker, detectedMetricAnomalies);
    return detectedMetricAnomalies;
  }

  private void detectMetricAnomaliesFromHistory(Map<BrokerEntity, List<Double>> historicalValue,
                                                Map<BrokerEntity, Double> currentValue,
                                                Set<BrokerEntity> detectedMetricAnomalies) {
    for (Map.Entry<BrokerEntity, Double> entry : currentValue.entrySet()) {
      BrokerEntity entity = entry.getKey();
      if (isDataSufficient(historicalValue.get(entity).size(), _metricHistoryPercentile, _metricHistoryPercentile)) {
        double [] data = historicalValue.get(entity).stream().mapToDouble(i -> i).toArray();
        _percentile.setData(data);
        if (currentValue.get(entity) > _percentile.evaluate(_metricHistoryPercentile) * _metricHistoryMargin) {
          detectedMetricAnomalies.add(entity);
        }
      }
    }
  }

  private void detectMetricAnomaliesFromPeers(Map<BrokerEntity, Double> currentValue,
                                              Set<BrokerEntity> detectedMetricAnomalies) {
    if (isDataSufficient(currentValue.size(), _peerMetricPercentile, _peerMetricPercentile)) {
      double [] data = currentValue.values().stream().mapToDouble(i -> i).toArray();
      _percentile.setData(data);
      double base = _percentile.evaluate(_peerMetricPercentile);
      for (Map.Entry<BrokerEntity, Double> entry : currentValue.entrySet()) {
        if (currentValue.get(entry.getKey()) > base * _peerMetricMargin) {
          detectedMetricAnomalies.add(entry.getKey());
        }
      }
    }
  }

  private SlowBrokers createSlowBrokersAnomaly(Map<BrokerEntity, Long> detectedBrokers,
                                               boolean fixable,
                                               boolean removeSlowBroker,
                                               String description) {
    Map<String, Object> parameterConfigOverrides = new HashMap<>(5);
    parameterConfigOverrides.put(KAFKA_CRUISE_CONTROL_OBJECT_CONFIG, _kafkaCruiseControl);
    parameterConfigOverrides.put(METRIC_ANOMALY_DESCRIPTION_OBJECT_CONFIG, description);
    parameterConfigOverrides.put(METRIC_ANOMALY_BROKER_ENTITIES_OBJECT_CONFIG, detectedBrokers);
    parameterConfigOverrides.put(REMOVE_SLOW_BROKER_CONFIG, removeSlowBroker);
    parameterConfigOverrides.put(ANOMALY_DETECTION_TIME_MS_OBJECT_CONFIG, _kafkaCruiseControl.timeMs());
    parameterConfigOverrides.put(METRIC_ANOMALY_FIXABLE_OBJECT_CONFIG, fixable);
    return _kafkaCruiseControl.config().getConfiguredInstance(AnomalyDetectorConfig.METRIC_ANOMALY_CLASS_CONFIG,
                                                              SlowBrokers.class,
                                                              parameterConfigOverrides);
  }

  private String getSlowBrokerDescription(Map<BrokerEntity, Long> detectedBrokers) {
    StringBuilder descriptionSb = new StringBuilder().append("{");
    detectedBrokers.forEach((key, value) -> {
      descriptionSb.append("Broker ").append(key.brokerId()).append("'s performance degraded at ").append(toDateString(value)).append(", ");
    });
    descriptionSb.setLength(descriptionSb.length() - 2);
    descriptionSb.append("}");
    return descriptionSb.toString();
  }

  @Override
  public Collection<MetricAnomaly<BrokerEntity>> metricAnomalies(Map<BrokerEntity, ValuesAndExtrapolations> metricsHistoryByBroker,
                                                                 Map<BrokerEntity, ValuesAndExtrapolations> currentMetricsByBroker) {
    LOG.info("Slow broker detection started.");
    try {
      Set<BrokerEntity> detectedMetricAnomalies = detectMetricAnomalies(metricsHistoryByBroker, currentMetricsByBroker);
      updateBrokerSlownessScore(detectedMetricAnomalies);
      return createSlowBrokerAnomalies(detectedMetricAnomalies, metricsHistoryByBroker.size());
    } catch (Exception e) {
      LOG.warn("Slow broker detector encountered exception: ", e);
    } finally {
      LOG.info("Slow broker detection finished.");
    }
    return Collections.emptySet();
  }

  private void updateBrokerSlownessScore(Set<BrokerEntity> detectedMetricAnomalies) {
    for (BrokerEntity broker : detectedMetricAnomalies) {
      // Update slow broker detection time and slowness score.
      Long currentTimeMs = _kafkaCruiseControl.timeMs();
      _detectedSlowBrokers.putIfAbsent(broker, currentTimeMs);
      _brokerSlownessScore.compute(broker, (k, v) -> (v == null) ? 1 : Math.min(v + 1, _slowBrokerDecommissionScore));
    }
    // For brokers which are previously detected as slow brokers, decrease their slowness score if their metrics has
    // recovered back to normal range.
    Set<BrokerEntity> brokersRecovered = new HashSet<>();
    for (Map.Entry<BrokerEntity, Integer> entry : _brokerSlownessScore.entrySet()) {
      BrokerEntity broker = entry.getKey();
      if (!detectedMetricAnomalies.contains(broker)) {
        Integer score = entry.getValue();
        if (score != null && --score == 0) {
          brokersRecovered.add(broker);
        } else {
          entry.setValue(score);
        }
      }
    }
    // If the broker has recovered, remove its suspicion.
    for (BrokerEntity broker : brokersRecovered) {
      _brokerSlownessScore.remove(broker);
      _detectedSlowBrokers.remove(broker);
    }
  }

  private Set<MetricAnomaly<BrokerEntity>> createSlowBrokerAnomalies(Set<BrokerEntity> detectedMetricAnomalies,
                                                                     int clusterSize) {
    Set<MetricAnomaly<BrokerEntity>> detectedSlowBrokers = new HashSet<>();
    Map<BrokerEntity, Long> brokersToDemote = new HashMap<>();
    Map<BrokerEntity, Long> brokersToRemove = new HashMap<>();

    for (BrokerEntity broker : detectedMetricAnomalies) {
      // Report anomaly if slowness score reaches threshold for broker decommission/demotion.
      int slownessScore = _brokerSlownessScore.get(broker);
      if (slownessScore == _slowBrokerDecommissionScore) {
        brokersToRemove.put(broker, _detectedSlowBrokers.get(broker));
      } else if (slownessScore >= _slowBrokerDemotionScore) {
        brokersToDemote.put(broker, _detectedSlowBrokers.get(broker));
      }
    }

    // If too many brokers in the cluster are detected as slow brokers, report anomaly as not fixable.
    // Otherwise report anomaly with brokers to be removed/demoted.
    if (brokersToDemote.size() + brokersToRemove.size() > clusterSize * _selfHealingUnfixableRatio) {
      brokersToRemove.forEach(brokersToDemote::put);
      detectedSlowBrokers.add(createSlowBrokersAnomaly(brokersToDemote, false, false, getSlowBrokerDescription(brokersToDemote)));
    } else {
      if (!brokersToDemote.isEmpty()) {
        detectedSlowBrokers.add(createSlowBrokersAnomaly(brokersToDemote, true, false, getSlowBrokerDescription(brokersToDemote)));
      }
      if (!brokersToRemove.isEmpty()) {
        detectedSlowBrokers.add(createSlowBrokersAnomaly(brokersToRemove,
                                                         _slowBrokerRemovalEnabled,
                                                         true,
                                                         getSlowBrokerDescription(brokersToRemove)));
      }
    }
    return detectedSlowBrokers;
  }

  @Override
  public void configure(Map<String, ?> configs) {
    _kafkaCruiseControl = (KafkaCruiseControl) configs.get(KAFKA_CRUISE_CONTROL_OBJECT_CONFIG);
    if (_kafkaCruiseControl == null) {
      throw new IllegalArgumentException("Slow broker detector is missing " + KAFKA_CRUISE_CONTROL_OBJECT_CONFIG);
    }
    // Config for slow broker removal.
    Map<String, Object> originalConfig = _kafkaCruiseControl.config().originals();
    _slowBrokerRemovalEnabled = Boolean.parseBoolean((String) originalConfig.get(SELF_HEALING_SLOW_BROKER_REMOVAL_ENABLED_CONFIG));

    String bytesInRateDetectionThreshold = (String) originalConfig.get(SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD_CONFIG);
    if (bytesInRateDetectionThreshold == null) {
      _bytesInRateDetectionThreshold = DEFAULT_SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD;
    } else {
      try {
        _bytesInRateDetectionThreshold = Double.parseDouble(bytesInRateDetectionThreshold);
        if (_bytesInRateDetectionThreshold < 0) {
          throw new ConfigException(String.format("%s config of slow broker finder should not be set to negative, provided: %f.",
                                                  SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD_CONFIG, _bytesInRateDetectionThreshold));
        }
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_BYTES_IN_RATE_DETECTION_THRESHOLD_CONFIG, bytesInRateDetectionThreshold, e.getMessage());
      }
    }

    String metricHistoryPercentile = (String) originalConfig.get(SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD_CONFIG);
    if (metricHistoryPercentile == null) {
      _metricHistoryPercentile = DEFAULT_SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD;
    } else {
      try {
        _metricHistoryPercentile = Double.parseDouble(metricHistoryPercentile);
        if (_metricHistoryPercentile < 0.0 || _metricHistoryPercentile > 100.0) {
          throw new ConfigException(String.format("%s config of slow broker finder should be set in range [0.0, 100.0], provided: %f.",
                                                  SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD_CONFIG, _metricHistoryPercentile));
        }
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_METRIC_HISTORY_PERCENTILE_THRESHOLD_CONFIG, metricHistoryPercentile, e.getMessage());
      }
    }

    String metricHistoryMargin = (String) originalConfig.get(SLOW_BROKER_METRIC_HISTORY_MARGIN_CONFIG);
    if (metricHistoryMargin == null) {
      _metricHistoryMargin = DEFAULT_SLOW_BROKER_METRIC_HISTORY_MARGIN;
    } else {
      try {
        _metricHistoryMargin = Double.parseDouble(metricHistoryMargin);
        if (_metricHistoryMargin < 1.0) {
          throw new ConfigException(String.format("%s config of slow broker finder should not be less than 1.0, provided: %f.",
                                                  SLOW_BROKER_METRIC_HISTORY_MARGIN_CONFIG, _metricHistoryMargin));
        }
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_METRIC_HISTORY_MARGIN_CONFIG, metricHistoryMargin, e.getMessage());
      }
    }

    String peerMetricPercentile = (String) originalConfig.get(SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD_CONFIG);
    if (peerMetricPercentile == null) {
      _peerMetricPercentile = DEFAULT_SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD;
    } else {
      try {
        _peerMetricPercentile = Double.parseDouble(peerMetricPercentile);
        if (_peerMetricPercentile < 0.0 || _peerMetricPercentile > 100.0) {
          throw new ConfigException(String.format("%s config of slow broker finder should be set in range [0.0, 100.0], provided: %f.",
                                                  SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD_CONFIG, _peerMetricPercentile));
        }
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_PEER_METRIC_PERCENTILE_THRESHOLD_CONFIG, peerMetricPercentile, e.getMessage());
      }
    }

    String peerMetricMargin = (String) originalConfig.get(SLOW_BROKER_PEER_METRIC_MARGIN_CONFIG);
    if (peerMetricMargin == null) {
      _peerMetricMargin = DEFAULT_SLOW_BROKER_PEER_METRIC_MARGIN;
    } else {
      try {
        _peerMetricMargin = Double.parseDouble(peerMetricMargin);
        if (_peerMetricMargin < 1.0) {
          throw new ConfigException(String.format("%s config of slow broker finder should not be less than 1.0, provided: %f.",
                                                  SLOW_BROKER_PEER_METRIC_MARGIN_CONFIG, _peerMetricPercentile));
        }
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_PEER_METRIC_MARGIN_CONFIG, peerMetricPercentile, e.getMessage());
      }
    }

    String slowBrokerDemotionScore = (String) originalConfig.get(SLOW_BROKER_DEMOTION_SCORE_CONFIG);
    if (slowBrokerDemotionScore == null) {
      _slowBrokerDemotionScore = DEFAULT_SLOW_BROKER_DEMOTION_SCORE;
    } else {
      try {
        _slowBrokerDemotionScore = Integer.parseUnsignedInt(slowBrokerDemotionScore);
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_DEMOTION_SCORE_CONFIG, slowBrokerDemotionScore, e.getMessage());
      }
    }

    String slowBrokerDecommissionScore = (String) originalConfig.get(SLOW_BROKER_DECOMMISSION_SCORE_CONFIG);
    if (slowBrokerDecommissionScore == null) {
      _slowBrokerDecommissionScore = DEFAULT_SLOW_BROKER_DECOMMISSION_SCORE;
    } else {
      try {
        _slowBrokerDecommissionScore = Integer.parseUnsignedInt(slowBrokerDecommissionScore);
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_DECOMMISSION_SCORE_CONFIG, slowBrokerDecommissionScore, e.getMessage());
      }
  }

    String selfHealingUnfixableRatio = (String) originalConfig.get(SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO_CONFIG);
    if (selfHealingUnfixableRatio == null) {
      _selfHealingUnfixableRatio = DEFAULT_SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO;
    } else {
      try {
        _selfHealingUnfixableRatio = Double.parseDouble(selfHealingUnfixableRatio);
        if (_selfHealingUnfixableRatio < 0.0 || _selfHealingUnfixableRatio > 1.0) {
          throw new ConfigException(String.format("%s config of slow broker finder should be set in range [0.0, 1.0], provided: %f.",
                                                  SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO_CONFIG, _selfHealingUnfixableRatio));
        }
      } catch (NumberFormatException e) {
        throw new ConfigException(SLOW_BROKER_SELF_HEALING_UNFIXABLE_RATIO_CONFIG, selfHealingUnfixableRatio, e.getMessage());
      }
    }
  }
}
