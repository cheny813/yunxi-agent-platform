package io.yunxi.agent.rule.repository;

import io.yunxi.agent.rule.model.RuleType;
import io.yunxi.agent.rule.model.Rule;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 规则仓库接口
 * 
 * 提供规则数据访问能力，支持按名称、类型、启用状态查询
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Repository
public interface RuleRepository {
    
    /**
     * 根据规则名称查找规则
     * 
     * @param name 规则名称
     * @return 规则Optional
     */
    Optional<Rule> findByName(String name);
    
    /**
     * 根据规则类型查找启用规则
     * 
     * @param type 规则类型
     * @return 规则列表
     */
    List<Rule> findByTypeAndEnabled(RuleType type, boolean enabled);
    
    /**
     * 查找所有启用规则
     * 
     * @return 启用规则列表
     */
    List<Rule> findByEnabled(boolean enabled);
    
    /**
     * 根据规则类型查找规则
     * 
     * @param type 规则类型
     * @return 规则列表
     */
    List<Rule> findByType(RuleType type);
    
    /**
     * 根据规则名称删除规则
     * 
     * @param name 规则名称
     */
    void deleteByName(String name);
    
    /**
     * 检查规则名称是否存在
     * 
     * @param name 规则名称
     * @return 是否存在
     */
    boolean existsByName(String name);
    
    /**
     * 保存规则
     * 
     * @param rule 规则对象
     * @return 保存后的规则
     */
    Rule save(Rule rule);
    
    /**
     * 查找所有规则
     * 
     * @return 规则列表
     */
    List<Rule> findAll();
    
    /**
     * 根据ID查找规则
     * 
     * @param id 规则ID
     * @return 规则Optional
     */
    Optional<Rule> findById(Long id);
    
    /**
     * 删除规则
     * 
     * @param rule 规则对象
     */
    void delete(Rule rule);
}