# 架构图

| 文件 | 说明 |
| --- | --- |
| `system-architecture.png` | README 中引用的系统架构图（2 倍高清） |
| `architecture-source.html` | 图的源文件，版式由 HTML + CSS 控制 |

## 为什么用静态图片而不是 Mermaid

GitHub 的 Mermaid 渲染器在以下情况会让整图渲染失败，页面只显示 `Unable to render rich display`：

- 图中存在多个 `subgraph` 分组
- 存在跨越分组边界的连线
- 节点与连线数量较多时，渲染容易触发超时

同样的代码在本地编辑器里可以正常显示，因此难以在提交前发现。改为静态图片后，版式完全可控，也不受渲染器版本变化影响。

## 重新生成

用 Chrome 无头模式按 2 倍缩放截图（尺寸需与内容匹配）：

```bash
chrome --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=2 \
  --window-size=984,512 \
  --screenshot=system-architecture.png architecture-source.html
```

修改 `architecture-source.html` 后重新执行上述命令即可更新图片。
