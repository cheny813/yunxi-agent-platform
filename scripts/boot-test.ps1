cd d:\work\code\yunxi-agent-platform
mvn -pl agent-app -am spring-boot:run -DskipTests `
  -Dserver.port=40001 `
  -DDASHSCOPE_API_KEY=sk-f0bd6466c7774fa3b053f9013da82cd5 `
  -DDASHSCOPE_MODEL=qwen3.8-27b `
  -DINTENT_MODEL=qwen-turbo `
  -DINTENT_TIMEOUT_MS=10000 `
  -Dspring.data.redis.host=127.0.0.1 `
  -Dspring.data.redis.port=6379 `
  -Dspring.data.redis.password=redispass `
  -Dspring.data.redis.database=0 `
  -Dyunxi.aistio.control-plane-url=http://127.0.0.1:8081 `
  -DLOG_DIR=logs
