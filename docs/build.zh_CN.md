# Windows 构建

使用 JDK/JBR 25 和仓库自带的 Gradle Wrapper，在仓库根目录执行 `./build.ps1`。脚本先清理产物，再检查许可证、编译主程序、构建 Sync/VNC 插件和运行时，最后执行 `jpackage`、`dist`。

脚本默认构建 Sync 和 VNC 插件。Migration 插件不在该脚本的构建列表中，如需其产物，可单独执行 `./gradlew.bat :plugins:migration:build`。

主程序 JAR 输出到 `build/main-jar/`，`copy-dependencies` 通过 `from(tasks.jar)` 将其与运行依赖复制到打包输入目录 `build/libs/`。分离两个任务的输出目录，避免联合构建插件时触发 Gradle 隐式依赖校验错误。`build.ps1` 的联合构建步骤依赖此 Gradle 配置。

仅验证主程序、插件和运行时，可执行：

```powershell
.\gradlew.bat clean
.\gradlew.bat :jar :copy-dependencies :plugins:sync:build :plugins:vnc:build :jlink
```

`jlink` 的输出目录必须不存在；重复生成运行时前先执行清理。上述检查不等同于完整安装包和发行产物验证。
