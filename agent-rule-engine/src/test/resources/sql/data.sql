-- 规则引擎测试数据初始化
-- H2数据库兼容版本

-- 插入测试规则数据
INSERT INTO rule (id, name, description, content, version, status, created_by, updated_by) VALUES
(1, 'age_validation_rule', '年龄验证规则', 'rule "Age Validation Rule"
when
    $person: Person(age < 18)
then
    $person.setValidationResult("未成年人需要家长监护");
end', 1, 'ACTIVE', 'testuser', 'testuser'),
(2, 'salary_bonus_rule', '薪资奖金计算规则', 'rule "Salary Bonus Rule"
when
    $employee: Employee(salary > 50000)
then
    $employee.setBonus($employee.getSalary() * 0.1);
end', 1, 'ACTIVE', 'testuser', 'testuser'),
(3, 'discount_calculation_rule', '折扣计算规则', 'rule "Discount Calculation Rule"
when
    $order: Order(totalAmount > 1000)
then
    $order.setDiscount(0.15);
end', 1, 'ACTIVE', 'testuser', 'testuser');

-- 插入测试规则执行日志数据
INSERT INTO rule_execution_log (rule_id, rule_name, input_data, output_data, execution_time, status, created_by) VALUES
(1, 'age_validation_rule', '{"person": {"name": "张三", "age": 16}}', '{"validationResult": "未成年人需要家长监护"}', 15, 'SUCCESS', 'testuser'),
(2, 'salary_bonus_rule', '{"employee": {"name": "李四", "salary": 60000}}', '{"bonus": 6000}', 8, 'SUCCESS', 'testuser'),
(3, 'discount_calculation_rule', '{"order": {"orderId": "ORD001", "totalAmount": 1500}}', '{"discount": 0.15}', 12, 'SUCCESS', 'testuser');