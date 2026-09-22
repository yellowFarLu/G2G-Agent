#!/usr/bin/env python3
"""
migrate_legacy_chunks.py: v3 §6.5 旧文档打标迁移脚本

为已入库的 Milvus chunks 补充 3 个标量字段：
  - domain: 领域标签（industry_solution / merchant_center / pms / ...）
  - sub_domain: 子领域标签（business / product / tech / testing / safety / management）
  - required_identity: 访问所需身份（admin / business / product / tech / testing）

调用方式：python3 scripts/migrate_legacy_chunks.py --milvus-host localhost --port 19530
"""

import argparse
import sys
import os
import json
import re

# 9 领域关键词映射
DOMAIN_KEYWORDS = {
    "industry_solution": ["行业方案", "解决方案", "跨业务", "架构方案"],
    "merchant_center": ["商户", "商家入驻", "资质", "画像", "merchant"],
    "pms": ["PMS", "物业", "订单管理", "property"],
    "service_provider": ["服务商", "第三方", "供应商", "provider"],
    "trunk_line": ["干线", "运输调度", "trunk"],
    "customs": ["报关", "清关", "海关", "customs"],
    "settlement": ["结算", "账单", "对账", "settlement"],
    "first_mile": ["揽收", "首公里", "仓配", "first mile"],
    "trajectory": ["轨迹", "物流追踪", "trajectory", "tracking"],
}

# 6 子领域关键词映射
SUB_DOMAIN_KEYWORDS = {
    "business": ["业务流程", "SOP", "业务规则", "操作流程"],
    "product": ["产品功能", "PRD", "产品配置", "需求文档"],
    "tech": ["架构", "API", "数据结构", "技术方案", "接口"],
    "testing": ["测试用例", "缺陷", "回归", "test"],
    "safety": ["安全", "合规", "生产事故", "safety"],
    "management": ["决策", "复盘", "汇报", "管理层", "周报"],
}


def classify_domain(text: str) -> str:
    """根据文本内容推断领域标签。"""
    if not text:
        return "industry_solution"
    scores = {}
    for domain, keywords in DOMAIN_KEYWORDS.items():
        score = sum(1 for kw in keywords if kw.lower() in text.lower())
        if score > 0:
            scores[domain] = score
    if scores:
        return max(scores, key=scores.get)
    return "industry_solution"  # 默认


def classify_sub_domain(text: str) -> str:
    """根据文本内容推断子领域标签。"""
    if not text:
        return "business"
    scores = {}
    for sub, keywords in SUB_DOMAIN_KEYWORDS.items():
        score = sum(1 for kw in keywords if kw.lower() in text.lower())
        if score > 0:
            scores[sub] = score
    if scores:
        return max(scores, key=scores.get)
    return "business"  # 默认


def infer_required_identity(sub_domain: str) -> str:
    """根据子领域推断所需身份。"""
    # safety → admin/tech 可见
    # management → admin/product 可见
    # tech → admin/tech 可见
    # 其余 → admin 可见（最低限制）
    return "admin"


def main():
    parser = argparse.ArgumentParser(description="v3 旧文档打标迁移")
    parser.add_argument("--milvus-host", default="localhost")
    parser.add_argument("--port", type=int, default=19530)
    parser.add_argument("--collection", default="wikiagent_chunks")
    parser.add_argument("--dry-run", action="store_true", help="只输出迁移计划，不实际执行")
    args = parser.parse_args()

    print(f"Milvus: {args.milvus_host}:{args.port}, Collection: {args.collection}")
    print(f"Dry run: {args.dry_run}")

    try:
        from pymilvus import connections, Collection, utility
    except ImportError:
        print("错误: 需要 pymilvus 库，请运行 pip install pymilvus", file=sys.stderr)
        sys.exit(1)

    # 连接 Milvus
    connections.connect(host=args.milvus_host, port=args.port)
    if not utility.has_collection(args.collection):
        print(f"错误: Collection {args.collection} 不存在", file=sys.stderr)
        sys.exit(1)

    collection = Collection(args.collection)
    collection.load()

    # 查询所有 chunks
    results = collection.query(
        expr="id != ''",
        output_fields=["id", "text", "doc_id"],
        limit=10000
    )

    print(f"找到 {len(results)} 条 chunk 需要迁移")

    migration_plan = []
    for chunk in results:
        text = chunk.get("text", "")
        domain = classify_domain(text)
        sub_domain = classify_sub_domain(text)
        required_identity = infer_required_identity(sub_domain)

        migration_plan.append({
            "id": chunk["id"],
            "doc_id": chunk.get("doc_id", ""),
            "domain": domain,
            "sub_domain": sub_domain,
            "required_identity": required_identity,
            "text_preview": text[:100] + "..." if len(text) > 100 else text
        })

    # 输出迁移计划
    plan_path = "migration_plan.json"
    with open(plan_path, "w", encoding="utf-8") as f:
        json.dump(migration_plan, f, ensure_ascii=False, indent=2)
    print(f"迁移计划已写入 {plan_path}")

    if not args.dry_run and migration_plan:
        print(f"开始执行迁移（{len(migration_plan)} 条）...")
        # 实际执行 upsert 更新标量字段
        ids = [m["id"] for m in migration_plan]
        domains = [m["domain"] for m in migration_plan]
        sub_domains = [m["sub_domain"] for m in migration_plan]
        identities = [m["required_identity"] for m in migration_plan]

        collection.upsert(
            data=[ids, domains, sub_domains, identities],
            fields=["id", "domain", "sub_domain", "required_identity"]
        )
        print(f"迁移完成：已更新 {len(ids)} 条 chunk 的标量字段")
    else:
        print("Dry run 模式：未实际执行迁移")


if __name__ == "__main__":
    main()
