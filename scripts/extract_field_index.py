#!/usr/bin/env python3
"""
extract_field_index.py: v1-v2 §4 长期记忆-交接清单 字段索引提取器

从 todo.json（交接清单）中提取所有被引用的字段名，构建 field_index.json
用于跨 session 快速查找"哪些任务引用了某个字段"。

调用方式：被 Java 的 PythonFieldIndexInvoker 在每次 todo.json 写入后调用：
  python3 scripts/extract_field_index.py <todo_dir> <field_index_output>

输入：todo.json 内容（含 executedNodes[].description + abandonedPaths[].reason + dataReferences）
输出：field_index.json（{ "field_name": [{ "session_id", "node_id", "doc_path" }] }）
"""

import json
import re
import sys
import os
from pathlib import Path
from collections import defaultdict


def extract_fields(text: str) -> list[str]:
    """从文本中提取类似字段名的模式：camelCase 或 snake_case 标识符。"""
    if not text:
        return []
    # camelCase / snake_case / dot.notation 字段名
    pattern = r'\b[a-z_][a-zA-Z0-9_]*(?:\.[a-z_][a-zA-Z0-9_]*)+\b'
    matches = re.findall(pattern, text)
    return list(set(matches))


def index_todo_file(todo_path: Path) -> dict:
    """解析单个 todo.json，提取所有字段引用。"""
    with open(todo_path, 'r', encoding='utf-8') as f:
        todo = json.load(f)

    session_id = todo.get('sessionId', '')
    field_index = defaultdict(list)

    # 从 executedNodes 提取
    for node in todo.get('executedNodes', []):
        node_id = node.get('nodeId', '')
        desc = node.get('description', '')
        for field in extract_fields(desc):
            field_index[field].append({
                'session_id': session_id,
                'node_id': node_id,
                'doc_path': str(todo_path),
            })

    # 从 abandonedPaths 提取
    for path in todo.get('abandonedPaths', []):
        reason = path.get('reason', '')
        for field in extract_fields(reason):
            field_index[field].append({
                'session_id': session_id,
                'node_id': path.get('nodeId', ''),
                'doc_path': str(todo_path),
            })

    # 从 dataReferences 提取
    for ref in todo.get('dataReferences', []):
        ref_str = json.dumps(ref, ensure_ascii=False)
        for field in extract_fields(ref_str):
            field_index[field].append({
                'session_id': session_id,
                'node_id': '',
                'doc_path': str(todo_path),
            })

    return dict(field_index)


def main():
    if len(sys.argv) < 3:
        print("用法: python3 extract_field_index.py <todo_dir> <field_index_output>")
        sys.exit(1)

    todo_dir = Path(sys.argv[1])
    output_path = Path(sys.argv[2])

    if not todo_dir.exists():
        print(f"错误: 目录不存在 {todo_dir}")
        sys.exit(1)

    # 合并所有 todo.json 的字段索引
    merged_index = defaultdict(list)

    for todo_file in todo_dir.rglob('todo.json'):
        try:
            partial = index_todo_file(todo_file)
            for field, refs in partial.items():
                merged_index[field].extend(refs)
        except (json.JSONDecodeError, IOError) as e:
            print(f"警告: 跳过 {todo_file}: {e}")
            continue

    # 写入 field_index.json
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(dict(merged_index), f, ensure_ascii=False, indent=2)

    print(f"字段索引已写入 {output_path}（{len(merged_index)} 个字段）")


if __name__ == '__main__':
    main()
