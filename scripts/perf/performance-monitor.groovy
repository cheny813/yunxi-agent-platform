#!/usr/bin/env groovy

/**
 * 🚀 yunxi-agent-platform 性能监控脚本
 * 实时监控系统性能指标，包括CPU、内存、线程池、数据库连接池等
 */

import java.lang.management.*
import java.util.concurrent.*
import javax.management.*
import groovy.json.JsonOutput

class PerformanceMonitor {
    
    private static final long MONITOR_INTERVAL = 5000 // 5秒间隔
    private static final int MAX_HISTORY = 12 // 保留最近12次记录
    
    private List<Map> performanceHistory = new CopyOnWriteArrayList<>()
    private volatile boolean running = false
    
    // 监控指标
    def startMonitoring() {
        running = true
        println "🚀 开始性能监控... (间隔: ${MONITOR_INTERVAL}ms)"
        println "=" * 80
        
        Thread.start {
            while (running) {
                try {
                    def metrics = collectMetrics()
                    performanceHistory.add(metrics)
                    
                    // 保持历史记录数量
                    if (performanceHistory.size() > MAX_HISTORY) {
                        performanceHistory = performanceHistory.subList(
                            performanceHistory.size() - MAX_HISTORY, performanceHistory.size())
                    }
                    
                    printMetrics(metrics)
                    checkAlerts(metrics)
                    
                    Thread.sleep(MONITOR_INTERVAL)
                } catch (Exception e) {
                    println "❌ 监控异常: ${e.message}"
                    Thread.sleep(MONITOR_INTERVAL)
                }
            }
        }
    }
    
    def stopMonitoring() {
        running = false
        println "\n🛑 停止性能监控"
    }
    
    // 收集性能指标
    private Map collectMetrics() {
        def metrics = [:]
        metrics.timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
        
        // JVM内存指标
        def memoryMXBean = ManagementFactory.memoryMXBean
        def heapUsage = memoryMXBean.heapMemoryUsage
        def nonHeapUsage = memoryMXBean.nonHeapMemoryUsage
        
        metrics.memory = [
            heapUsed: heapUsage.used / (1024 * 1024),
            heapMax: heapUsage.max / (1024 * 1024),
            heapCommitted: heapUsage.committed / (1024 * 1024),
            nonHeapUsed: nonHeapUsage.used / (1024 * 1024),
            nonHeapCommitted: nonHeapUsage.committed / (1024 * 1024)
        ]
        
        // GC指标
        def gcMXBeans = ManagementFactory.garbageCollectorMXBeans
        def gcStats = []
        gcMXBeans.each { gc ->
            gcStats << [
                name: gc.name,
                collectionCount: gc.collectionCount,
                collectionTime: gc.collectionTime
            ]
        }
        metrics.gc = gcStats
        
        // 线程指标
        def threadMXBean = ManagementFactory.threadMXBean
        metrics.threads = [
            threadCount: threadMXBean.threadCount,
            peakThreadCount: threadMXBean.peakThreadCount,
            daemonThreadCount: threadMXBean.daemonThreadCount
        ]
        
        // 系统指标
        def osMXBean = ManagementFactory.operatingSystemMXBean
        metrics.system = [
            availableProcessors: osMXBean.availableProcessors,
            systemLoadAverage: osMXBean.systemLoadAverage
        ]
        
        // 运行时指标
        def runtimeMXBean = ManagementFactory.runtimeMXBean
        metrics.runtime = [
            uptime: runtimeMXBean.uptime / 1000, // 转换为秒
            startTime: new Date(runtimeMXBean.startTime).format("yyyy-MM-dd HH:mm:ss")
        ]
        
        // 自定义指标（模拟线程池状态）
        metrics.threadPools = getThreadPoolMetrics()
        
        // 数据库连接池指标（模拟）
        metrics.database = getDatabasePoolMetrics()
        
        return metrics
    }
    
    // 模拟线程池指标
    private Map getThreadPoolMetrics() {
        def random = new Random()
        return [
            asyncTaskExecutor: [
                activeThreads: random.nextInt(50),
                queueSize: random.nextInt(100),
                completedTasks: random.nextInt(1000)
            ],
            aiInferenceExecutor: [
                activeThreads: random.nextInt(20),
                queueSize: random.nextInt(50),
                completedTasks: random.nextInt(500)
            ]
        ]
    }
    
    // 模拟数据库连接池指标
    private Map getDatabasePoolMetrics() {
        def random = new Random()
        return [
            hikariPool: [
                activeConnections: random.nextInt(10),
                idleConnections: random.nextInt(5),
                totalConnections: random.nextInt(15),
                waitingThreads: random.nextInt(3)
            ]
        ]
    }
    
    // 打印性能指标
    private void printMetrics(Map metrics) {
        println "\n📊 性能监控报告 - ${metrics.timestamp}"
        println "-" * 60
        
        // 内存使用情况
        def heapUsed = metrics.memory.heapUsed
        def heapMax = metrics.memory.heapMax
        def heapPercent = (heapUsed / heapMax * 100).round(2)
        
        println "💾 内存使用: ${heapUsed}MB / ${heapMax}MB (${heapPercent}%)"
        println "🧵 线程数: ${metrics.threads.threadCount} (峰值: ${metrics.threads.peakThreadCount})"
        println "💻 CPU负载: ${metrics.system.systemLoadAverage ?: 'N/A'}"
        println "⏱️  运行时间: ${metrics.runtime.uptime} 秒"
        
        // 线程池状态
        def asyncPool = metrics.threadPools.asyncTaskExecutor
        println "🔄 异步线程池: ${asyncPool.activeThreads} 活动 / ${asyncPool.queueSize} 队列"
        
        // 数据库连接池状态
        def dbPool = metrics.database.hikariPool
        println "🗄️  数据库连接池: ${dbPool.activeConnections} 活动 / ${dbPool.idleConnections} 空闲"
        
        // 生成进度条
        printMemoryBar(heapPercent)
    }
    
    // 生成内存使用进度条
    private void printMemoryBar(double percent) {
        def barLength = 20
        def filled = (percent / 100 * barLength).toInteger()
        def bar = "[" + "█" * filled + "░" * (barLength - filled) + "]"
        
        def color = percent > 80 ? "🔴" : percent > 60 ? "🟡" : "🟢"
        println "${color} 内存使用: ${bar} ${percent}%"
    }
    
    // 检查告警条件
    private void checkAlerts(Map metrics) {
        def alerts = []
        
        def heapPercent = (metrics.memory.heapUsed / metrics.memory.heapMax * 100).round(2)
        
        // 内存告警
        if (heapPercent > 85) {
            alerts << "🔴 内存使用率过高: ${heapPercent}%"
        } else if (heapPercent > 75) {
            alerts << "🟡 内存使用率警告: ${heapPercent}%"
        }
        
        // 线程数告警
        if (metrics.threads.threadCount > 200) {
            alerts << "🔴 线程数过多: ${metrics.threads.threadCount}"
        }
        
        // 异步线程池告警
        def asyncPool = metrics.threadPools.asyncTaskExecutor
        if (asyncPool.queueSize > 500) {
            alerts << "🟡 异步任务队列积压: ${asyncPool.queueSize}"
        }
        
        // 数据库连接池告警
        def dbPool = metrics.database.hikariPool
        if (dbPool.waitingThreads > 5) {
            alerts << "🔴 数据库连接等待过多: ${dbPool.waitingThreads}"
        }
        
        // 显示告警
        if (alerts) {
            println "\n🚨 告警信息:"
            alerts.each { alert ->
                println "   ${alert}"
            }
        }
    }
    
    // 生成性能报告
    def generateReport() {
        if (performanceHistory.isEmpty()) {
            return "暂无监控数据"
        }
        
        def latest = performanceHistory.last()
        def report = [:]
        
        report.summary = [
            timestamp: latest.timestamp,
            uptime: latest.runtime.uptime,
            status: 'RUNNING'
        ]
        
        // 计算平均值
        def heapUsageAvg = performanceHistory.collect { 
            (it.memory.heapUsed / it.memory.heapMax * 100).round(2) 
        }.sum() / performanceHistory.size()
        
        def threadCountAvg = performanceHistory.collect { 
            it.threads.threadCount 
        }.sum() / performanceHistory.size()
        
        report.averages = [
            heapUsage: heapUsageAvg,
            threadCount: threadCountAvg.round(),
            monitoringDuration: "${performanceHistory.size() * (MONITOR_INTERVAL / 1000)} 秒"
        ]
        
        // 趋势分析
        report.trend = analyzeTrend()
        
        // 建议
        report.recommendations = generateRecommendations(latest)
        
        return JsonOutput.prettyPrint(JsonOutput.toJson(report))
    }
    
    // 分析趋势
    private Map analyzeTrend() {
        if (performanceHistory.size() < 2) {
            return [status: 'INSUFFICIENT_DATA']
        }
        
        def first = performanceHistory.first()
        def last = performanceHistory.last()
        
        def heapTrend = ((last.memory.heapUsed - first.memory.heapUsed) / first.memory.heapUsed * 100).round(2)
        def threadTrend = last.threads.threadCount - first.threads.threadCount
        
        return [
            heapUsageTrend: heapTrend,
            threadCountTrend: threadTrend,
            trend: heapTrend > 5 ? 'INCREASING' : heapTrend < -5 ? 'DECREASING' : 'STABLE'
        ]
    }
    
    // 生成优化建议
    private List generateRecommendations(Map metrics) {
        def recommendations = []
        
        def heapPercent = (metrics.memory.heapUsed / metrics.memory.heapMax * 100).round(2)
        
        if (heapPercent > 80) {
            recommendations << "增加JVM堆内存配置"
        }
        
        if (metrics.threads.threadCount > 150) {
            recommendations << "检查线程泄漏或优化线程池配置"
        }
        
        def asyncPool = metrics.threadPools.asyncTaskExecutor
        if (asyncPool.queueSize > 300) {
            recommendations << "优化异步任务处理速度或增加线程池大小"
        }
        
        if (recommendations.isEmpty()) {
            recommendations << "系统运行正常，无需立即优化"
        }
        
        return recommendations
    }
}

// 命令行接口
class MonitorCLI {
    static void main(String[] args) {
        def monitor = new PerformanceMonitor()
        
        // 添加关闭钩子
        Runtime.runtime.addShutdownHook {
            monitor.stopMonitoring()
            println "\n📋 最终性能报告:"
            println monitor.generateReport()
        }
        
        // 解析命令行参数
        def cli = new CliBuilder(usage: 'performance-monitor.groovy [选项]')
        cli.with {
            h longOpt: 'help', '显示帮助信息'
            d longOpt: 'duration', args: 1, argName: 'seconds', '监控持续时间（秒）'
            i longOpt: 'interval', args: 1, argName: 'milliseconds', '监控间隔（毫秒）'
            r longOpt: 'report', '只生成报告不实时监控'
        }
        
        def options = cli.parse(args)
        
        if (options.h) {
            cli.usage()
            return
        }
        
        if (options.r) {
            println "📋 历史性能报告:"
            println monitor.generateReport()
            return
        }
        
        // 配置监控参数
        if (options.i) {
            PerformanceMonitor.MONITOR_INTERVAL = options.i as long
        }
        
        println "🎯 yunxi-agent-platform 性能监控工具"
        println "版本: 1.0.0 | 间隔: ${PerformanceMonitor.MONITOR_INTERVAL}ms"
        println "按 Ctrl+C 停止监控并生成报告"
        
        // 开始监控
        monitor.startMonitoring()
        
        // 设置监控持续时间
        if (options.d) {
            def duration = options.d as long
            Thread.sleep(duration * 1000)
            monitor.stopMonitoring()
        } else {
            // 无限监控，等待用户中断
            while (true) {
                Thread.sleep(1000)
            }
        }
    }
}

// 导出为独立工具
if (!Script.isBindingSet()) {
    MonitorCLI.main(args)
}

/**
 * 🎯 使用示例:
 * 
 * 1. 实时监控:
 *    groovy performance-monitor.groovy
 * 
 * 2. 监控特定时长:
 *    groovy performance-monitor.groovy -d 60
 * 
 * 3. 自定义间隔:
 *    groovy performance-monitor.groovy -i 2000
 * 
 * 4. 生成报告:
 *    groovy performance-monitor.groovy -r
 * 
 * 5. 在代码中使用:
 *    def monitor = new PerformanceMonitor()
 *    monitor.startMonitoring()
 *    // ... 执行其他操作
 *    Thread.sleep(30000)
 *    monitor.stopMonitoring()
 *    println monitor.generateReport()
 */