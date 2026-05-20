"""ReMe 服务启动包装脚本。

功能：
1. 加载 ReMePython/config.yaml 中的 Milvus 配置
2. 自动创建 Milvus 数据库（如 ReMeDB）
3. 注册 Milvus 向量存储后端到 ReMe 的 registry
4. 将配置转换为 CLI 参数格式传递给 ReMeApp
5. 启动 ReMe 服务

用法（由 启动ReMe服务.bat 调用）：
    python start_reme.py http.port=8002 llm.default.model_name=...
"""

import os
import sys
import yaml

# 注册 Milvus 向量存储后端到 ReMe 的全局 registry
import ReMePython.register


def load_config() -> dict | None:
    """加载 config.yaml 并返回原始配置字典。

    Returns:
        配置字典，文件不存在时返回 None
    """
    config_path = os.path.join(os.path.dirname(__file__), "config.yaml")

    if not os.path.exists(config_path):
        print(f"[WARN] 配置文件不存在: {config_path}")
        return None

    with open(config_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)

    return config if config else None


def ensure_milvus_database(config: dict | None):
    """检查并自动创建 Milvus 数据库（如 ReMeDB）。

    从 config.yaml 中读取数据库配置，如果数据库不存在则自动创建，
    避免人工创建步骤。
    """
    if config is None:
        return

    vs_config = config.get("vector_store", {})
    for store_config in vs_config.values():
        backend = store_config.get("backend", "")
        if backend != "milvus":
            continue

        params = store_config.get("params", {})
        db_name = params.get("db_name", "default")
        uri = params.get("uri", "http://localhost:19530")
        token = params.get("token")

        if db_name == "default":
            return  # 默认库无需创建

        try:
            from pymilvus import connections, db as milvus_db

            conn_kwargs = {"uri": uri, "alias": "default"}
            if token:
                conn_kwargs["token"] = token

            connections.connect(**conn_kwargs)
            databases = milvus_db.list_database()
            if db_name not in databases:
                milvus_db.create_database(db_name)
                print(f"[INFO] Milvus 数据库已自动创建: {db_name}")
            else:
                print(f"[INFO] Milvus 数据库已存在: {db_name}")
            connections.disconnect("default")
        except Exception as e:
            print(f"[WARN] 检查/创建 Milvus 数据库失败: {e}")
            print("[WARN] ReMe 服务可能无法连接到 Milvus，将尝试使用 memory 后端兜底")
        return  # 只处理第一个 milvus 配置


def config_to_cli_args(config: dict | None) -> list[str]:
    """将配置字典转换为 ReMe CLI 参数列表。

    Returns:
        CLI 参数列表，如 ["vector_store.default.backend=milvus", ...]
    """
    if config is None:
        return []

    cli_args = []

    # 转换 vector_store 配置
    # 格式: vector_store.<name>.<key>=<value>
    vs_config = config.get("vector_store", {})
    for name, store_config in vs_config.items():
        prefix = f"vector_store.{name}"
        if "backend" in store_config:
            cli_args.append(f"{prefix}.backend={store_config['backend']}")
        if "embedding_model" in store_config:
            cli_args.append(f"{prefix}.embedding_model={store_config['embedding_model']}")
        if "collection_name" in store_config:
            cli_args.append(f"{prefix}.collection_name={store_config['collection_name']}")
        # 转换 params 子配置
        # 格式: vector_store.<name>.params.<key>=<value>
        params = store_config.get("params", {})
        for key, value in params.items():
            cli_args.append(f"{prefix}.params.{key}={value}")

    return cli_args


def main():
    """启动 ReMe 服务的主入口。

    流程：
    1. 从 config.yaml 加载 Milvus 配置
    2. 自动创建 Milvus 数据库（如 ReMeDB）
    3. 将配置转为 CLI 参数
    4. 合并命令行传入的额外参数（优先级更高）
    5. 调用 reme_ai.main.main() 启动服务
    """
    # 1. 从配置文件加载
    config = load_config()
    config_args = config_to_cli_args(config)

    # 2. 自动创建 Milvus 数据库
    ensure_milvus_database(config)

    # 3. 合并命令行参数（命令行参数优先级更高，放在后面）
    # sys.argv[1:] 是 bat 中传入的额外参数，如 http.port=8002 等
    all_args = config_args + sys.argv[1:]

    print("=== ReMe 启动参数 ===")
    for arg in all_args:
        print(f"  {arg}")
    print("=====================")

    # 4. 替换 sys.argv 并启动 ReMe 服务
    sys.argv = [sys.argv[0]] + all_args

    # 5. 导入并启动 ReMe
    from reme_ai.main import main as reme_main
    reme_main()


if __name__ == "__main__":
    main()