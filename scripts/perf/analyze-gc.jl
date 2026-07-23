#!/usr/bin/env julia
# ====================================================
# GC日志分析工具 - 自动检测内存使用模式和GC效率
# ====================================================

using StatsBase
using Dates

function analyze_gc_log(file_path)
    if !isfile(file_path)
        println("❌ 文件不存在: $file_path")
        return
    end
    
    println("📊 正在分析GC日志: $file_path")
    println("="^60)
    
    # 解析GC日志
    gc_events = []
    minor_gc_count = 0
    major_gc_count = 0
    total_pause_time = 0.0
    
    lines = readlines(file_path)
    for line in lines
        if occursin("GC(", line) && occursin("Pause", line)
            # 解析GC事件
            gc_type = contains(line, "Young") ? "Minor" : contains(line, "Full") ? "Major" : "Other"
            
            # 解析暂停时间 (类似: Pause Young 0.015ms)
            pause_match = match(r"Pause[^0-9]*([0-9]+\.[0-9]+)ms", line)
            pause_time = pause_match != nothing ? parse(Float64, pause_match.captures[1]) : 0.0
            
            total_pause_time += pause_time
            
            if gc_type == "Minor"
                minor_gc_count += 1
            elseif gc_type == "Major"
                major_gc_count += 1
            end
            
            push!(gc_events, (gc_type, pause_time))
        end
    end
    
    total_gc_count = length(gc_events)
    avg_pause_time = total_gc_count > 0 ? total_pause_time / total_gc_count : 0
    
    # 输出分析结果
    println("📈 GC统计概要:")
    println("   - 总GC次数: $total_gc_count")
    println("   - Minor GC: $minor_gc_count")
    println("   - Major GC: $major_gc_count")
    println("   - 总暂停时间: $(round(total_pause_time, digits=2))ms")
    println("   - 平均暂停时间: $(round(avg_pause_time, digits=2))ms")
    
    # 性能评级
    println("\n🏆 性能评级:")
    if total_gc_count == 0
        println("   ✅ 优秀 - 未检测到明显的GC活动")
    elseif avg_pause_time < 10 && major_gc_count == 0
        println("   ✅ 良好 - GC效率高，暂停时间短")
    elseif avg_pause_time < 50
        println("   ⚠️  一般 - 建议优化内存配置")
    else
        println("   ❌ 较差 - 需要立即优化内存设置")
    end
    
    # 优化建议
    println("\n💡 优化建议:")
    if major_gc_count > minor_gc_count
        println("   • 增加堆内存大小 (-Xmx)")
        println("   • 优化对象生命周期管理")
        println("   • 检查内存泄漏")
    end
    
    if avg_pause_time > 20
        println("   • 调整G1GC的MaxGCPauseMillis参数")
        println("   • 考虑使用ZGC或ShenandoahGC")
    end
    
    if total_gc_count > 100
        println("   • 检查是否有过度创建临时对象")
        println("   • 优化缓存策略")
    end
    
    println("="^60)
end

# 主程序
function main()
    gc_log_path = "./logs/gc.log"
    
    if !isdir("./logs")
        println("📁 创建logs目录...")
        mkdir("./logs")
    end
    
    if !isdir("./heapdumps")
        println("📁 创建heapdumps目录...")
        mkdir("./heapdumps")
    end
    
    if isfile(gc_log_path)
        analyze_gc_log(gc_log_path)
    else
        println("📝 GC日志文件不存在，将在应用启动后生成")
        println("💡 启动应用后运行此脚本查看分析结果")
    end
end

# 运行分析
main()