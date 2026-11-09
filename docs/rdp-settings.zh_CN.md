# Windows RDP 主机设置

在 Windows 上编辑或新建 RDP 主机时，从左侧切换「属性」「RDP」「代理」和「跳板机」。配置按主机保存，下次连接时应用。

- 在「属性」中选择连接方式。「内嵌（mstscax）」在 Termora 标签页打开，也是已有主机的默认方式。「外部（mstsc.exe）」打开 Windows 自带的远程桌面连接窗口。
- 在「RDP」中将分辨率设为「固定」，输入宽高，例如 `1920 × 1080`。每个维度支持 200 至 8192 像素。旧主机保存过有效宽高的，会在这里显示为固定分辨率。
- 分辨率选「自适应」时，内嵌会话随标签页大小调整远程分辨率，外部窗口的分辨率由 Windows 客户端决定。勾选「画面适应窗口」可在本地缩放画面。配合固定分辨率时，远程桌面的宽高保持固定。
- 「远程桌面缩放」设置远程会话的 DPI 缩放，可跟随本机，也可指定百分比。远端系统和客户端决定是否接受该设置。微软正在弃用外部客户端使用的 `desktopscalefactor` 属性。
- 「颜色深度」是连接时向 RDP 客户端提交的请求。微软文档说明，在 Windows 客户端使用 RDP 8.0 或更新版本连接 Windows 会话主机时，会话使用 32 位色。[Microsoft 的颜色深度策略说明](https://learn.microsoft.com/en-us/windows/client-management/mdm/policy-csp-admx-terminalserver#ts_colordepth)
- 同一页还可设置远程音频的播放位置、麦克风、剪贴板、打印机、驱动器、智能卡以及持久位图缓存。资源重定向受远端策略约束。新版 Windows 的外部客户端会在连接前显示安全对话框，需要在那里勾选要共享的本地资源。[Microsoft 的 RDP 文件安全提示说明](https://learn.microsoft.com/en-us/windows-server/remote/remote-desktop-services/remotepc/understanding-security-warnings)

验收时可用同一台测试主机分别验证两种连接方式。将分辨率设为 `1920 × 1080` 并连接，调整本地窗口，再检查远程桌面的分辨率。切换为「自适应」并重新连接内嵌会话，检查分辨率随标签页大小变化。可分别开关「画面适应窗口」，观察固定分辨率下的显示效果。

内嵌 ActiveX 的显示设置没有每连接刷新率或帧率选项。Windows Server 管理员可以在远程会话主机上通过 `DWMFRAMEINTERVAL` 调整帧率上限。实际帧率取决于应用、硬件和网络。[Microsoft 的远程会话帧率说明](https://learn.microsoft.com/en-us/troubleshoot/windows-server/remote/frame-rate-limited-to-30-fps)

[Microsoft 的 RDP 显示和重定向属性](https://learn.microsoft.com/en-us/azure/virtual-desktop/rdp-properties)

## 代理和 SSH 跳板机

内嵌和外部连接均可使用「代理」页的 HTTP、SOCKS5，或「跳板机」页中的一台或多台已保存的 SSH 主机。同一条 RDP 主机只能选其中一种路线。跳板机按列表顺序连接，最后一台需允许 TCP 转发并能访问 RDP 主机。如果入口 SSH 主机需要代理，在那台 SSH 主机的设置里填写。

HTTP 代理需允许向目标端口建立 `CONNECT` 隧道，可用无认证或 Basic 用户名密码。SOCKS5 支持无认证和用户名密码。代理或跳板失败时，连接会中止。

转发监听本机回环地址，只转发 TCP。Windows 可能因为连接地址与证书名称不同而显示服务器身份提示。核对证书对应的是配置里的目标主机后，再自行决定是否继续。使用 IP 连接且需要指定远程 Windows 本地账号时，可填写 `电脑名\用户名`。通过外部 `mstsc.exe` 使用转发时，保持 Termora 运行。
