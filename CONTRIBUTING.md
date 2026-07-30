# Contributing

感谢你为 `data-collection-service` 做贡献。

## 提交前建议

- 先搜索已有 Issue，避免重复建设
- 大改动先提 Issue 说明目标、范围和兼容性影响
- 涉及协议、调度、缓存、上报、配置治理的修改，默认需要同步补文档

## 本地验证

提交前建议至少执行：

```bash
mvn -B -ntp "-Dspring-boot.repackage.skip=true" verify
```

## Pull Request 要求

- PR 尽量保持单一目标
- 写清楚背景、改动点、验证方式、兼容性影响
- 不要把无关重构和功能修复混在一起

## 协议相关改动

如果你新增或修改协议能力，请尽量补充：

- 关键配置字段
- 最小配置样例
- 已知限制
- 已验证设备或测试方式