package com.wangbin.collector.core.collector.scheduler;

import com.wangbin.collector.common.domain.entity.DataPoint;
import lombok.extern.slf4j.Slf4j;

/**
 * 自适应采集工具类
 * 实现数据变化率计算和采集频率自适应调整
 */
@Slf4j
public class AdaptiveCollectionUtil {

    /**
     * 默认基础采集间隔（毫秒）。
     */
    public static final long DEFAULT_BASE_COLLECTION_INTERVAL = 2000;
    
    /**
     * 默认最小采集间隔（毫秒）
     */
    public static final long DEFAULT_MIN_COLLECTION_INTERVAL = 100;
    
    /**
     * 默认最大采集间隔（毫秒）
     */
    public static final long DEFAULT_MAX_COLLECTION_INTERVAL = 3600000; // 1小时
    
    /**
     * 默认变化率阈值（百分比）
     */
    public static final double DEFAULT_CHANGE_THRESHOLD = 1.0;
    
    /**
     * 默认连续稳定次数阈值
     */
    public static final int DEFAULT_STABLE_THRESHOLD = 5;
    
    /**
     * 默认调整窗口（毫秒）
     */
    public static final long DEFAULT_ADJUST_WINDOW = 60000; // 1分钟
    
    /**
     * 计算数据变化率
     * 
     * @param currentValue 当前采集值
     * @param lastValue    上次采集值
     * @return 变化率（百分比），值为0表示无变化，值为100表示变化100%
     */
    public static double calculateChangeRate(Object currentValue, Object lastValue) {
        if (currentValue == null || lastValue == null) {
            return 0.0;
        }
        
        // 相同值直接返回0
        if (currentValue.equals(lastValue)) {
            return 0.0;
        }
        
        try {
            // 尝试转换为数值类型计算变化率
            double curr = Double.parseDouble(currentValue.toString());
            double last = Double.parseDouble(lastValue.toString());
            
            // 处理last为0的情况
            if (Math.abs(last) < 1e-6) {
                // 如果last为0，使用绝对差值作为变化率
                return Math.abs(curr - last);
            }
            
            // 计算相对变化率
            return Math.abs((curr - last) / last) * 100.0;
        } catch (NumberFormatException e) {
            // 非数值类型，变化即为100%
            return 100.0;
        }
    }
    
    /**
     * 根据数据变化率调整采集间隔
     *
     * @param dataPoint    数据点对象
     * @param currentValue 当前采集值
     * @param changeRate   计算得到的变化率
     */
    /**
     * 初始化数据点的自适应采集配置
     * 
     * @param dataPoint         数据点对象
     */
    public static void normalizeConfiguration(DataPoint dataPoint) {
        if (dataPoint == null) {
            throw new IllegalArgumentException("数据点不能为空");
        }

        long baseInterval = normalizePositive(dataPoint.getBaseCollectionInterval(), DEFAULT_BASE_COLLECTION_INTERVAL);
        long minInterval = normalizePositive(dataPoint.getMinCollectionInterval(), DEFAULT_MIN_COLLECTION_INTERVAL);
        long maxInterval = normalizePositive(dataPoint.getMaxCollectionInterval(), DEFAULT_MAX_COLLECTION_INTERVAL);
        if (minInterval > maxInterval) {
            long tmp = minInterval;
            minInterval = maxInterval;
            maxInterval = tmp;
        }
        baseInterval = Math.max(minInterval, Math.min(baseInterval, maxInterval));
        double changeThreshold = dataPoint.getPointChangeThreshold() != null
                ? dataPoint.getPointChangeThreshold()
                : DEFAULT_CHANGE_THRESHOLD;

        dataPoint.setBaseCollectionInterval(baseInterval);
        dataPoint.setMinCollectionInterval(minInterval);
        dataPoint.setMaxCollectionInterval(maxInterval);
        dataPoint.setPointChangeThreshold(changeThreshold);
        log.debug("数据点 {} 自适应采集配置已规范化，基础间隔 {}ms，最小 {}ms，最大 {}ms，变化阈值 {}%",
                dataPoint.getPointId(), dataPoint.getBaseCollectionInterval(), dataPoint.getMinCollectionInterval(),
                dataPoint.getMaxCollectionInterval(), dataPoint.getChangeThreshold());
    }
    
    /**
     * 检查是否需要调整采集间隔
     * 
     * @param lastAdjustTime 上次调整时间
     * @param adjustWindow   调整窗口（毫秒）
     * @return true：需要调整，false：不需要调整
     */
    public static boolean needAdjust(long lastAdjustTime, long adjustWindow) {
        return System.currentTimeMillis() - lastAdjustTime > adjustWindow;
    }
    
    /**
     * 解析或转换业务数据。
     */
    private static long normalizePositive(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

}
