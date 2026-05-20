package io.yunxi.agent.rule.repository;

import io.yunxi.agent.rule.model.RuleType;
import io.yunxi.agent.rule.model.Rule;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存规则仓库
 * 
 * 提供基于内存的规则存储实现，适用于开发测试环境
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Repository
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
public class InMemoryRuleRepository implements RuleRepository {
    
    /**
     * 规则存储映射表（规则名称 -> 规则对象）
     */
    private final Map<String, Rule> ruleMap;
    
    /**
     * 按规则类型分组的规则映射表
     */
    private final Map<RuleType, Set<Rule>> rulesByType;
    
    public InMemoryRuleRepository() {
        this.ruleMap = new ConcurrentHashMap<>();
        this.rulesByType = new ConcurrentHashMap<>();
        
        // 初始化按类型分组的容器
        for (RuleType type : RuleType.values()) {
            rulesByType.put(type, Collections.synchronizedSet(new HashSet<>()));
        }
    }
    
    /**
     * 保存规则
     * 
     * @param rule 规则对象
     * @return 保存后的规则
     */
    public Rule save(Rule rule) {
        if (rule == null || rule.getName() == null) {
            throw new IllegalArgumentException("规则或规则名称不能为空");
        }
        
        // 保存到主映射表
        ruleMap.put(rule.getName(), rule);
        
        // 按类型保存
        Set<Rule> typeRules = rulesByType.computeIfAbsent(rule.getType(), 
            k -> Collections.synchronizedSet(new HashSet<>()));
        
        // 移除同名的旧规则（如果存在）
        typeRules.removeIf(r -> r.getName().equals(rule.getName()));
        typeRules.add(rule);
        
        return rule;
    }
    
    /**
     * 查找规则
     * 
     * @param id 规则ID
     * @return 规则Optional
     */
    public Optional<Rule> findById(Long id) {
        return ruleMap.values().stream()
            .filter(rule -> Objects.equals(rule.getId(), id))
            .findFirst();
    }
    
    /**
     * 根据规则名称查找规则
     * 
     * @param name 规则名称
     * @return 规则Optional
     */
    public Optional<Rule> findByName(String name) {
        return Optional.ofNullable(ruleMap.get(name));
    }
    
    /**
     * 查找所有规则
     * 
     * @return 规则列表
     */
    public List<Rule> findAll() {
        return new ArrayList<>(ruleMap.values());
    }
    
    /**
     * 根据规则类型查找规则
     * 
     * @param type 规则类型
     * @return 规则列表
     */
    public List<Rule> findByType(RuleType type) {
        Set<Rule> typeRules = rulesByType.get(type);
        return typeRules != null ? new ArrayList<>(typeRules) : new ArrayList<>();
    }
    
    /**
     * 查找启用规则
     * 
     * @param enabled 是否启用
     * @return 规则列表
     */
    public List<Rule> findByEnabled(boolean enabled) {
        return ruleMap.values().stream()
            .filter(rule -> rule.isEnabled() == enabled)
            .toList();
    }
    
    /**
     * 根据规则类型和启用状态查找规则
     * 
     * @param type 规则类型
     * @param enabled 是否启用
     * @return 规则列表
     */
    public List<Rule> findByTypeAndEnabled(RuleType type, boolean enabled) {
        Set<Rule> typeRules = rulesByType.get(type);
        if (typeRules == null) {
            return new ArrayList<>();
        }
        
        return typeRules.stream()
            .filter(rule -> rule.isEnabled() == enabled)
            .toList();
    }
    
    /**
     * 删除规则
     * 
     * @param id 规则ID
     */
    public void deleteById(Long id) {
        findById(id).ifPresent(rule -> {
            ruleMap.remove(rule.getName());
            
            Set<Rule> typeRules = rulesByType.get(rule.getType());
            if (typeRules != null) {
                typeRules.remove(rule);
            }
        });
    }
    
    /**
     * 删除规则
     * 
     * @param rule 规则对象
     */
    public void delete(Rule rule) {
        if (rule != null && rule.getName() != null) {
            deleteByName(rule.getName());
        }
    }
    
    /**
     * 根据规则名称删除规则
     * 
     * @param name 规则名称
     */
    public void deleteByName(String name) {
        Rule rule = ruleMap.remove(name);
        if (rule != null) {
            Set<Rule> typeRules = rulesByType.get(rule.getType());
            if (typeRules != null) {
                typeRules.remove(rule);
            }
        }
    }
    
    /**
     * 检查规则名称是否存在
     * 
     * @param name 规则名称
     * @return 是否存在
     */
    public boolean existsByName(String name) {
        return ruleMap.containsKey(name);
    }
    
    /**
     * 获取规则总数
     * 
     * @return 规则总数
     */
    public long count() {
        return ruleMap.size();
    }
    
    /**
     * 清除所有规则
     */
    public void deleteAll() {
        ruleMap.clear();
        for (Set<Rule> typeRules : rulesByType.values()) {
            typeRules.clear();
        }
    }
}