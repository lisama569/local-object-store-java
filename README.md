# local-object-store-java

本地磁盘 + 内存元数据模拟的对象存储：分片上传（中断续传/乱序/重试）、内容去重、
多版本并发控制、容量配额、租户隔离、重启一致性与异常归一化。

## 运行与测试

```bash
mvn spring-boot:run          # 启动，默认数据目录 data/objectstore
mvn test                     # 30 个用例：续传/重复分片/并发覆盖/配额/校验失败/隔离/重启
```

## 配置（环境变量覆盖）

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `OBJECTSTORE_HOME` | `data/objectstore` | 数据根目录 |
| `OBJECTSTORE_CAPACITY` | `104857600` | 全仓容量（字节） |
| `OBJECTSTORE_MAX_PART` | `8388608` | 默认单分片上限（字节） |
| `OBJECTSTORE_MAX_PARTS` | `1000` | 单次上传分片数上限 |

详细设计（存储布局/去重/版本/配额/错误码/API/验证方法）见 [docs/DESIGN.md](docs/DESIGN.md)。
