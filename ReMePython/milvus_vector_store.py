"""Milvus vector store implementation for flowllm.

Implements flowllm's BaseVectorStore interface using Milvus as the backend.
Each workspace maps to a separate Milvus collection.
Uses @C.register_vector_store("milvus") decorator for registration.
"""

import json
import os
from pathlib import Path
from typing import Any, List, Optional

from loguru import logger

from flowllm.core.context import C
from flowllm.core.vector_store.base_vector_store import BaseVectorStore
from flowllm.core.embedding_model import BaseEmbeddingModel
from flowllm.core.schema import VectorNode

_MILVUS_IMPORT_ERROR = None

try:
    from pymilvus import MilvusClient, DataType
except ImportError as e:
    _MILVUS_IMPORT_ERROR = e
    MilvusClient = None
    DataType = None


@C.register_vector_store("milvus")
class MilvusVectorStore(BaseVectorStore):
    """Milvus-backed vector store implementing flowllm's BaseVectorStore.

    Each workspace is stored as a separate Milvus collection.
    Collection names are prefixed with "workspace_" to avoid conflicts.
    """

    def __init__(
        self,
        embedding_model: BaseEmbeddingModel | None = None,
        uri: str = "http://localhost:19530",
        token: str | None = None,
        user: str | None = None,
        password: str | None = None,
        db_name: str = "default",
        **kwargs,
    ):
        if _MILVUS_IMPORT_ERROR is not None:
            raise ImportError(
                "Milvus requires extra dependencies. Install with: pip install pymilvus",
            ) from _MILVUS_IMPORT_ERROR

        super().__init__(embedding_model=embedding_model, **kwargs)

        self.client: MilvusClient | None = None
        self._uri = uri
        self._token = token
        self._user = user
        self._password = password
        self._db_name = db_name

        # Initialize client immediately (not deferred like ReMe's pattern)
        conn_kwargs = {"uri": self._uri, "db_name": self._db_name}
        if self._token:
            conn_kwargs["token"] = self._token
        elif self._user and self._password:
            conn_kwargs["user"] = self._user
            conn_kwargs["password"] = self._password

        self.client = MilvusClient(**conn_kwargs)
        logger.info(f"Milvus client initialized (uri={self._uri}, db={self._db_name})")

    # ── Helpers ────────────────────────────────────────────────────────

    @staticmethod
    def _workspace_to_collection(workspace_id: str) -> str:
        """Convert a workspace ID to a Milvus collection name."""
        return f"workspace_{workspace_id}"

    def _ensure_collection(self, collection_name: str):
        """Create the collection if it doesn't exist."""
        collections = self.client.list_collections()
        if collection_name in collections:
            return
        dim = self.embedding_model.dimensions if self.embedding_model else 1024
        schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("vector_id", DataType.VARCHAR, max_length=64, is_primary=True)
        schema.add_field("content", DataType.VARCHAR, max_length=65535)
        schema.add_field("metadata", DataType.JSON)
        schema.add_field("vector", DataType.FLOAT_VECTOR, dim=dim)
        index_params = MilvusClient.prepare_index_params()
        index_params.add_index("vector", "IVF_FLAT", metric_type="IP", params={"nlist": 128})
        self.client.create_collection(collection_name, schema=schema, index_params=index_params)

    @staticmethod
    def _build_filter_expr(filter_dict: dict | None) -> str | None:
        """Convert a filter dict to a Milvus filter expression."""
        if not filter_dict:
            return None
        conditions = []
        for key, value in filter_dict.items():
            mk = f"metadata['{key}']"
            if isinstance(value, list) and len(value) == 2:
                if isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
                    conditions.append(f"{mk} >= {value[0]} and {mk} <= {value[1]}")
            elif isinstance(value, dict) and ("gte" in value or "lte" in value):
                parts = []
                if "gte" in value and isinstance(value["gte"], (int, float)):
                    parts.append(f"{mk} >= {value['gte']}")
                if "lte" in value and isinstance(value["lte"], (int, float)):
                    parts.append(f"{mk} <= {value['lte']}")
                if parts:
                    conditions.append(" and ".join(parts))
            else:
                if isinstance(value, str):
                    conditions.append(f"{mk} == '{value}'")
                elif isinstance(value, bool):
                    conditions.append(f"{mk} == {str(value).lower()}")
                else:
                    conditions.append(f"{mk} == {value}")
        return " and ".join(conditions) if conditions else None

    def _row_to_node(self, row: dict) -> VectorNode:
        return VectorNode(
            unique_id=row.get("vector_id", ""),
            content=row.get("content", ""),
            vector=row.get("vector"),
            metadata=row.get("metadata", {}),
        )

    # ── Workspace Management ───────────────────────────────────────────

    def exist_workspace(self, workspace_id: str, **kwargs) -> bool:
        cname = self._workspace_to_collection(workspace_id)
        return cname in self.client.list_collections()

    async def async_exist_workspace(self, workspace_id: str, **kwargs) -> bool:
        return self.exist_workspace(workspace_id, **kwargs)

    def create_workspace(self, workspace_id: str, **kwargs):
        cname = self._workspace_to_collection(workspace_id)
        self._ensure_collection(cname)

    async def async_create_workspace(self, workspace_id: str, **kwargs):
        self.create_workspace(workspace_id, **kwargs)

    def delete_workspace(self, workspace_id: str, **kwargs):
        cname = self._workspace_to_collection(workspace_id)
        if cname in self.client.list_collections():
            self.client.drop_collection(cname)

    async def async_delete_workspace(self, workspace_id: str, **kwargs):
        self.delete_workspace(workspace_id, **kwargs)

    def list_workspace(self, **kwargs) -> List[str]:
        prefix = "workspace_"
        return [
            c[len(prefix):]
            for c in self.client.list_collections()
            if c.startswith(prefix)
        ]

    async def async_list_workspace(self, **kwargs) -> List[str]:
        return self.list_workspace(**kwargs)

    def list_workspace_nodes(self, workspace_id: str, **kwargs) -> List[VectorNode]:
        cname = self._workspace_to_collection(workspace_id)
        if cname not in self.client.list_collections():
            return []
        results = self.client.query(cname, output_fields=["vector_id", "content", "metadata", "vector"])
        return [self._row_to_node(r) for r in results]

    async def async_list_workspace_nodes(self, workspace_id: str, **kwargs) -> List[VectorNode]:
        return self.list_workspace_nodes(workspace_id, **kwargs)

    # ── Data Operations ────────────────────────────────────────────────

    def insert(self, nodes: VectorNode | List[VectorNode], workspace_id: str, **kwargs):
        cname = self._workspace_to_collection(workspace_id)
        self._ensure_collection(cname)
        if isinstance(nodes, VectorNode):
            nodes = [nodes]
        data = []
        for n in nodes:
            data.append({
                "vector_id": n.unique_id,
                "content": n.content,
                "metadata": n.metadata,
                "vector": n.vector,
            })
        self.client.insert(cname, data=data)

    async def async_insert(self, nodes: VectorNode | List[VectorNode], workspace_id: str, **kwargs):
        self.insert(nodes, workspace_id, **kwargs)

    def search(
        self,
        query: str,
        workspace_id: str,
        top_k: int = 1,
        filter_dict: Optional[dict] = None,
        **kwargs,
    ) -> List[VectorNode]:
        cname = self._workspace_to_collection(workspace_id)
        if cname not in self.client.list_collections():
            return []

        if not query:
            # Filter-only search (no vector similarity)
            expr = self._build_filter_expr(filter_dict)
            results = self.client.query(cname, filter=expr, limit=top_k,
                                         output_fields=["vector_id", "content", "metadata", "vector"])
            return [self._row_to_node(r) for r in results]

        # Vector similarity search
        query_vector = self.embedding_model.get_embeddings(query) if self.embedding_model else None
        if query_vector is None:
            return []

        if isinstance(query_vector, list) and not isinstance(query_vector[0], (int, float)):
            query_vector = query_vector[0]

        expr = self._build_filter_expr(filter_dict)
        results = self.client.search(
            cname,
            data=[query_vector],
            anns_field="vector",
            search_params={"metric_type": "IP", "params": {"nprobe": 10}},
            limit=top_k,
            filter=expr,
            output_fields=["vector_id", "content", "metadata"],
        )
        nodes = []
        if results:
            for hits in results:
                for hit in hits:
                    entity = hit.get("entity", {})
                    node = VectorNode(
                        vector_id=entity.get("vector_id", hit.get("id", "")),
                        content=entity.get("content", ""),
                        metadata=entity.get("metadata", {}),
                    )
                    node.metadata["score"] = hit.get("distance", 0.0)
                    nodes.append(node)
        return nodes

    async def async_search(
        self,
        query: str,
        workspace_id: str,
        top_k: int = 1,
        filter_dict: Optional[dict] = None,
        **kwargs,
    ) -> List[VectorNode]:
        return self.search(query, workspace_id, top_k, filter_dict, **kwargs)

    def delete(self, node_ids: str | List[str], workspace_id: str, **kwargs):
        cname = self._workspace_to_collection(workspace_id)
        if cname not in self.client.list_collections():
            return
        if isinstance(node_ids, str):
            node_ids = [node_ids]
        expr = f"vector_id in {node_ids}"
        self.client.delete(cname, filter=expr)

    async def async_delete(self, node_ids: str | List[str], workspace_id: str, **kwargs):
        self.delete(node_ids, workspace_id, **kwargs)

    # ── Dump / Load ────────────────────────────────────────────────────

    def dump_workspace(self, workspace_id: str, path: str | Path = "", callback_fn=None, **kwargs):
        nodes = self.list_workspace_nodes(workspace_id)
        if not path:
            path = f"workspace_{workspace_id}_dump.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump([n.model_dump() for n in nodes], f, ensure_ascii=False, default=str)
        if callback_fn:
            callback_fn(len(nodes))

    async def async_dump_workspace(self, workspace_id: str, path: str | Path = "", callback_fn=None, **kwargs):
        self.dump_workspace(workspace_id, path, callback_fn, **kwargs)

    def load_workspace(
        self,
        workspace_id: str,
        path: str | Path = "",
        nodes: List[VectorNode] | None = None,
        callback_fn=None,
        **kwargs,
    ):
        cname = self._workspace_to_collection(workspace_id)
        self._ensure_collection(cname)
        if nodes is None and path:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            nodes = [VectorNode(**item) for item in data]
        if nodes:
            self.insert(nodes, workspace_id)
            if callback_fn:
                callback_fn(len(nodes))

    async def async_load_workspace(
        self,
        workspace_id: str,
        path: str | Path = "",
        nodes: List[VectorNode] | None = None,
        callback_fn=None,
        **kwargs,
    ):
        self.load_workspace(workspace_id, path, nodes, callback_fn, **kwargs)

    def copy_workspace(self, src_workspace_id: str, dest_workspace_id: str, **kwargs):
        nodes = self.list_workspace_nodes(src_workspace_id)
        if nodes:
            self.insert(nodes, dest_workspace_id)

    async def async_copy_workspace(self, src_workspace_id: str, dest_workspace_id: str, **kwargs):
        self.copy_workspace(src_workspace_id, dest_workspace_id, **kwargs)

    # ── Lifecycle ──────────────────────────────────────────────────────

    def close(self):
        if self.client:
            self.client.close()
            self.client = None
            logger.info("Milvus client closed")

    async def async_close(self):
        self.close()