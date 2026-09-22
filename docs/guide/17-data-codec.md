# 17. 统一 JSON 编解码机制

yunxi 所有 Redis 值的 JSON 编解码已统一收口，不再由各模块各自配置 `ObjectMapper` 或序列化器。本机制是平台级「单一真相源」约束的一部分：同一份数据在写入与读取侧必须表现一致，否则差异只在特定字段上暴露、极难定位。

## 为什么统一

散落的绑定器配置（各处 `new ObjectMapper()`、各自 `JsonMapper.builder()`、自行配 `RedisTemplate` 的序列化器）会让同一份数据在不同组件里出现不同的读写行为：时间字段有时是时间戳有时是 ISO 字符串、未知字段有时报错有时忽略、枚举有时是名称有时是序号。这类差异只在特定数据上触发，难以复现与排查。

统一后的三层结构，每一层都只有一个合法入口：

| 层 | 类 | 职责 | 约束 |
|----|----|------|------|
| JSON 绑定来源 | `CodecSpec` | 全平台 `ObjectMapper` 的唯一配置来源 | 禁止 `new ObjectMapper()` / `JsonMapper.builder()` |
| Redis 值序列化 | `RedisValueCodec` | 全平台 Redis 值的唯一序列化真相源，实现 `RedisSerializer<Object>` | 禁止自行配置 `RedisSerializer` |
| 模板构造入口 | `RedisTemplates` | 全平台 `RedisTemplate` 的唯一构造入口 | 禁止 `new RedisTemplate()` / `new StringRedisTemplate()` |

## 三层结构

### CodecSpec —— JSON 绑定的唯一来源

所有需要 JSON 编解码的组件都从 `CodecSpec` 取绑定器，提供两种形态：

- `CodecSpec.storage()`（持久化绑定器）：用于写入 Redis、数据库等持久化介质的值编解码。特点：**不写入类型信息**（结果只含 JSON 原生类型）；**关闭未知字段失败**（`FAIL_ON_UNKNOWN_PROPERTIES=false`，使持久化对象可增字段而不破坏历史数据读取）；**注册 Java 时间模块**（时间以 ISO 字符串而非时间戳表示）。
- `CodecSpec.structure()`（结构转换绑定器）：用于把领域对象转为普通结构（映射/列表/标量），不直接落盘。配置最小、不受持久化格式演进影响。

写入持久化的结构应经 `CodecSpec.toNative(Object, ObjectMapper)` 收窄为仅含 JSON 原生类型的值：递归处理映射、集合、数组；枚举取名称；其余领域对象先转为普通结构再递归收窄；转换失败退化为字符串表示、不抛异常。

### RedisValueCodec —— Redis 值序列化的唯一真相源

所有 Redis 值编解码一律经 `RedisValueCodec`，不得另行配置序列化器或手写 JSON 拼装。契约如下：

- **不写入类型信息**：序列化结果只含 JSON 原生类型，不附加类型标识。读取侧不依赖任何领域类型可实例化 —— 持久化数据的可用性不应取决于读取环境是否具备某个类。若序列化结果中出现 `"@class"` 类型标识，`RedisValueCodec.containsTypeTag(...)` 可检出，意味着写入侧越界。
- **写入侧收窄**：进入 Redis 的值应先经 `toNative(Object)` 规整；`write(Object, String)` 对结果做类型标识体检计数，使越界写入可被观测。
- **读取侧逐条容错**：`convertList(Collection, Class, String)` 对列表元素逐条转换，单条失败只跳过该条并计数，不使整批数据不可读。
- **显式声明目标形状**：读取用 `read(String, Class, String)` / `readMap(byte[], String)` 显式指定目标类型，使「存储里是什么」与「读成什么」之间的转换可见、可控。

`RedisValueCodec` 通过场景标识（`SCENE_PROFILE` / `SCENE_TRACE` / `SCENE_CACHE` / `SCENE_GENERIC`）对失败计数分桶，失败由 `CodecMetrics` 统一计数与上报（见下文「可观测性」）。

### RedisTemplates —— 模板的唯一构造入口

平台只允许两种模板形态：对象级（值经 `RedisValueCodec` 编解码）与字符串级（明文字符串直存）。需要 Redis 访问的组件一律经本类构造：

- `RedisTemplates.objectTemplate(factory, codec)`：键明文、值经统一编解码器。用于对象型数据（会话、轨迹）。
- `RedisTemplates.stringTemplate(factory)`：键值均为明文。适用于纯文本数据（画像 JSON、节流计数）；若数据是对象，应改用 `objectTemplate` 让值经编解码器处理，不要在此自行拼接 JSON。

两个方法都在无连接工厂时返回 `null`，由调用方据此回退到进程内实现 —— 这是开发与单测（不起 Redis）下的既定降级路径。

## 使用方

| 消费方 | 模块 | 通道 | 场景标识 / 键前缀 |
|--------|------|------|--------------------|
| 会话存储 | `RedisConfig` | `RedisTemplates.objectTemplate` | 值经 `RedisValueCodec` |
| 推理轨迹 | `TraceStoreAutoConfiguration` / `RedisTraceStore` / `TraceComposer` | `RedisTemplates.objectTemplate` | `SCENE_TRACE`，键前缀 `yunxi:trace:` |
| 用户画像 | `RedisUserProfileStore` | `RedisTemplates.stringTemplate`（值经 `codec.write/read`） | `SCENE_PROFILE` |
| 画像演进节流 | `ProfileEvolutionThrottle` | `RedisTemplates.stringTemplate` | 字符串计数 |

四项均复用同一个全局 `RedisConnectionFactory`（Spring Boot 从 `config/redis.yml` 的 `spring.data.redis.*` 自动装配），不存在第二套连接。会话 Redis、轨迹 Redis、画像、节流本质落在同一物理实例，区别只是不同的 `RedisTemplate` 对象与键前缀。

## 可观测性

编解码失败发生在旁路（轨迹、画像、节流状态），以「返回空 / 跳过该条 / 记一条 warn 日志」降级，降级本身不报错，不会中断执行主流程。为使其可观测而非表现为消费方拿到空数据：

- `CodecMetrics` 对失败按场景分桶计数，并上报 micrometer 指标 `yunxi.codec.failures`（标签 `scene`）。累计达到 10 次时打一条 warn 日志（偶发失败来自历史脏数据属预期容错，持续失败才意味着读写两侧不一致）。
- 装配日志与诊断接口统一打印实际 Redis 实例端点，便于确认「是否命中了同一个实例」：
  - 启动日志：`RedisConfig` 与 `TraceStoreAutoConfiguration` 均打印 `目标 Redis 实例=host:port`。
  - 运行期接口：`GET /api/traces/diagnostics`（已对外单独放行，无需凭证）返回 `storeEndpoint`，形如 `127.0.0.1:6379 (键前缀=yunxi:trace:)`。若此处显示内存或与你预期的连接参数不符，说明应用连的 Redis 与预期实例不是同一个，查询不到轨迹属正常，请核对 `spring.data.redis.host/port`。

## 开发约束

新增任何 Redis 值读写时：

- JSON 绑定一律从 `CodecSpec.storage()` / `CodecSpec.structure()` 取，禁止 `new ObjectMapper()` 或 `JsonMapper.builder()`。
- Redis 值序列化一律经 `RedisValueCodec`，禁止自行配置 `RedisSerializer` 或手写 JSON 拼装。
- `RedisTemplate` 一律经 `RedisTemplates.objectTemplate` / `stringTemplate` 构造，禁止 `new RedisTemplate()` / `new StringRedisTemplate()`；复制这段构造代码是引入第二套序列化配置的入口。
- 值进入 Redis 前经 `toNative` 收窄；读取列表时用 `convertList` 逐条容错。

## 与框架 Session 存储的区别

本机制统一的是 **yunxi 自有**的 Redis 值编解码（会话存储、轨迹、画像、节流）。AgentScope-Java 框架的 Session 存储（`agentscope.core.session.type=redis`）走框架自身的 `RedisTemplateAdapter`，是另一套独立关注点，不属于本统一机制的范围。两者复用同一全局 `RedisConnectionFactory`，但序列化约定与键空间各自独立。
