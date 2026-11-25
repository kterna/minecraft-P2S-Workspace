# 旧工具协议快照

`legacy-tools.json` 从工具目录拆分前已编译的 `LLMService.buildSessionToolDefinitions(true, null)` 离线导出（`LLMService` 已在 pi 核心替换时删除），包含 21 个工具的完整声明及顺序。导出未发出任何模型请求。

测试用它独立核对新工具目录和传输请求。只有有意修改工具协议并完成评审时才更新快照，不要直接用待验证的新实现覆盖它来消除测试差异。
