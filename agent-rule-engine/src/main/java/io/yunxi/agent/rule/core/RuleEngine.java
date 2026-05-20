package io.yunxi.agent.rule.core;

import io.yunxi.agent.rule.exception.RuleViolationException;
import io.yunxi.agent.rule.model.Rule;
import io.yunxi.agent.rule.model.RuleResult;
import io.yunxi.agent.rule.model.RuleType;
import io.yunxi.agent.rule.repository.RuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.api.RulesEngine;
import org.jeasy.rules.api.RulesEngineParameters;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 规则引擎门面
 * 
 * 提供统一的规则执行入口，支持前置/运行时/后置规则检查
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class RuleEngine {
    
    /**
     * EasyRules 引擎实例
     */
    private RulesEngine rulesEngine;
    
    /**
     * 规则注册表（按类型分组）
     */
    private final Map<RuleType, Rules> rulesByType;
    
    /**
     * 规则仓库
     */
    private final RuleRepository ruleRepository;
    
    /**
     * 规则执行监听器列表
     */
    private final List<RuleExecutionListener> listeners;
    
    public RuleEngine(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
        this.rulesByType = new ConcurrentHashMap<>();
        this.listeners = new ArrayList<>();
        
        // 初始化按类型分组的规则容器
        for (RuleType type : RuleType.values()) {
            rulesByType.put(type, new Rules());
        }
    }
    
    /**
     * 初始化规则引擎
     */
    @PostConstruct
    public void init() {
        log.info("初始化规则引擎...");
        
        // 配置规则引擎参数
        RulesEngineParameters parameters = new RulesEngineParameters()
            .skipOnFirstFailedRule(true)  // 第一个规则失败后跳过后续规则
            .skipOnFirstAppliedRule(false); // 不跳过已应用的规则
        
        this.rulesEngine = new DefaultRulesEngine(parameters);
        
        // 加载内置规则
        loadBuiltinRules();
        
        // 加载数据库规则
        loadDatabaseRules();
        
        log.info("规则引擎初始化完成，已加载 {} 个规则", getTotalRuleCount());
    }
    
    /**
     * 执行规则检查
     * 
     * @param type 规则类型
     * @param facts 规则事实
     * @param context 规则上下文
     * @return 规则执行结果
     */
    public RuleResult executeRules(RuleType type, org.jeasy.rules.api.Facts facts, RuleContext context) {
        // 为上下文设置规则类型
        context.setRuleType(type);
        
        // 将传入的事实合并到上下文中
        facts.asMap().forEach((key, value) -> {
            context.setAttribute(key, value);
        });
        
        // 直接执行对应类型的规则
        return executeRules(type, context);
    }
    
    /**
     * 执行运行时规则监控
     * 
     * @param context 规则上下文
     * @return 规则执行结果
     */
    public RuleResult monitorRuntimeRules(RuleContext context) {
        return executeRules(RuleType.RUNTIME, context);
    }
    
    /**
     * 执行后置规则验证
     * 
     * @param context 规则上下文
     * @return 规则执行结果
     */
    public RuleResult checkPostRules(RuleContext context) {
        return executeRules(RuleType.POST, context);
    }
    
    /**
     * 动态添加规则
     * 
     * @param rule 规则实体
     */
    public void addRule(Rule rule) {
        // 转换为 EasyRules 规则
        org.jeasy.rules.api.Rule easyRule = rule.toEasyRule();
        
        // 注册到对应类型的规则容器
        RuleType type = rule.getType();
        rulesByType.get(type).register(easyRule);
        
        // 持久化到数据库
        ruleRepository.save(rule);
        
        log.info("规则已添加: {} (类型: {}, 优先级: {})", 
            rule.getName(), type, rule.getPriority());
    }
    
    /**
     * 动态移除规则
     * 
     * @param ruleName 规则名称
     */
    public void removeRule(String ruleName) {
        // 从所有类型容器中移除
        for (Rules rules : rulesByType.values()) {
            try {
                rules.unregister(ruleName);
            } catch (Exception e) {
                // 规则可能不在该容器中，忽略异常
            }
        }
        
        // 从数据库删除
        ruleRepository.deleteByName(ruleName);
        
        log.info("规则已移除: {}", ruleName);
    }
    
    /**
     * 启用规则
     * 
     * @param ruleName 规则名称
     */
    public void enableRule(String ruleName) {
        ruleRepository.findByName(ruleName).ifPresent(rule -> {
            rule.setEnabled(true);
            ruleRepository.save(rule);
            
            // 重新加载规则
            addRule(rule);
            
            log.info("规则已启用: {}", ruleName);
        });
    }
    
    /**
     * 禁用规则
     * 
     * @param ruleName 规则名称
     */
    public void disableRule(String ruleName) {
        ruleRepository.findByName(ruleName).ifPresent(rule -> {
            rule.setEnabled(false);
            ruleRepository.save(rule);
            
            // 从引擎中移除
            removeRule(ruleName);
            
            log.info("规则已禁用: {}", ruleName);
        });
    }
    
    /**
     * 获取所有规则
     * 
     * @return 规则列表
     */
    public List<Rule> getAllRules() {
        return ruleRepository.findAll();
    }
    
    /**
     * 获取指定类型的规则
     * 
     * @param type 规则类型
     * @return 规则列表
     */
    public List<Rule> getRulesByType(RuleType type) {
        return ruleRepository.findByType(type);
    }
    
    /**
     * 注册规则执行监听器
     * 
     * @param listener 监听器
     */
    public void registerListener(RuleExecutionListener listener) {
        listeners.add(listener);
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 执行规则
     */
    private RuleResult executeRules(RuleType type, RuleContext context) {
        context.setRuleType(type);
        
        long startTime = System.currentTimeMillis();
        List<String> triggeredRules = new ArrayList<>();
        
        try {
            // 触发前置监听器
            notifyListeners(listener -> listener.beforeRuleExecution(type, context));
            
            // 执行规则
            Rules rules = rulesByType.get(type);
            rulesEngine.fire(rules, context.toFacts());
            
            // 收集触发的规则
            // 注意：EasyRules 不直接提供触发的规则列表，需要通过监听器实现
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            // 触发后置监听器
            notifyListeners(listener -> listener.afterRuleExecution(type, context, executionTime));
            
            return RuleResult.builder()
                .passed(true)
                .triggeredRules(triggeredRules)
                .build();
                
        } catch (RuleViolationException e) {
            // 规则违反异常
            long executionTime = System.currentTimeMillis() - startTime;
            
            log.warn("规则违反: {} - {}", type, e.getMessage());
            
            // 触发异常监听器
            notifyListeners(listener -> listener.onRuleViolation(type, context, e));
            
            return RuleResult.builder()
                .passed(false)
                .errorMessage(e.getMessage())
                .triggeredRules(triggeredRules)
                .build();
                
        } catch (Exception e) {
            // 其他异常
            log.error("规则执行失败: {}", e.getMessage(), e);
            
            return RuleResult.builder()
                .passed(false)
                .errorMessage("规则执行失败: " + e.getMessage())
                .triggeredRules(triggeredRules)
                .build();
        }
    }
    
    /**
     * 加载内置规则
     */
    private void loadBuiltinRules() {
        log.info("加载内置规则...");
        
        // 通过 Spring 自动注入所有 RuleDefinition Bean
        // 这里需要在配置类中实现自动注册
        
        log.info("内置规则加载完成");
    }
    
    /**
     * 加载数据库规则
     */
    private void loadDatabaseRules() {
        log.info("加载数据库规则...");
        
        List<Rule> dbRules = ruleRepository.findAll();
        dbRules.stream()
            .filter(Rule::isEnabled)
            .forEach(rule -> {
                org.jeasy.rules.api.Rule easyRule = rule.toEasyRule();
                rulesByType.get(rule.getType()).register(easyRule);
            });
        
        log.info("数据库规则加载完成，共 {} 个", 
            dbRules.stream().filter(Rule::isEnabled).count());
    }
    
    /**
     * 获取规则总数
     */
    private int getTotalRuleCount() {
        return rulesByType.values().stream()
            .mapToInt(rules -> (int) StreamSupport.stream(rules.spliterator(), false).count())
            .sum();
    }
    
    /**
     * 通知所有监听器
     */
    private void notifyListeners(java.util.function.Consumer<RuleExecutionListener> action) {
        listeners.forEach(action);
    }
}