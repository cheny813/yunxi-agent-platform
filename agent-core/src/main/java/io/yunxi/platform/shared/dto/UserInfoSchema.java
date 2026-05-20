package io.yunxi.platform.shared.dto;

/**
 * 用户信息 Schema（用于结构化输出示例）
 * <p>
 * 此类定义了结构化输出的数据格式
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
public class UserInfoSchema {

    /**
     * 用户名
     */
    public String name;

    /**
     * 年龄
     */
    public Integer age;

    /**
     * 邮箱
     */
    public String email;

    /**
     * 技能列表
     */
    public java.util.List<String> skills;

    /**
     * 部门
     */
    public String department;

    /**
     * 薪资
     */
    public Double salary;

    public UserInfoSchema() {
    }

    @Override
    public String toString() {
        return "UserInfoSchema{" +
                "name='" + name + '\'' +
                ", age=" + age +
                ", email='" + email + '\'' +
                ", skills=" + skills +
                ", department='" + department + '\'' +
                ", salary=" + salary +
                '}';
    }
}
