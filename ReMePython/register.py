"""Register Milvus vector store with flowllm's context registry.

Importing this module triggers the @C.register_vector_store("milvus")
decorator on MilvusVectorStore, registering it with flowllm's service
context so that C.get_vector_store_class("milvus") returns the class.

Usage:
    import ReMePython.register   # triggers registration
    from reme_ai.main import main
    main()
"""

# The import triggers @C.register_vector_store("milvus") decorator
from ReMePython.milvus_vector_store import MilvusVectorStore  # noqa: F401