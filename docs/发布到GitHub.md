# 发布到 GitHub（一次性配置 + 以后的更新流程）

> 目标：**朋友从 GitHub 下载，不用你再一个个传文件。**
> 仓库里**不含**任何服务器地址、令牌或 cookie（这是发布前专门检查过的）。

---

## 一、一次性配置（约 3 分钟）

### 1. 在 GitHub 上建空仓库

打开 <https://github.com/new>：

- **Repository name**：`clouddisc`（或你喜欢的名字）
- **Public / Private**：想省事让朋友直接下载就选 **Public**
- **不要**勾选 "Add a README file"（本地已经有了，勾了会冲突）
- 点 **Create repository**

### 2. 本地把项目推上去

在项目目录（`...\clouddisc`）打开终端，执行：

```bash
git init
git add .
git commit -m "歪歪网易云唱片 0.7.0 首次提交"
git branch -M main
git remote add origin https://github.com/<你的用户名>/clouddisc.git
git push -u origin main
```

> 推送时会要求登录：GitHub 现在不接受账号密码，
> 请用 **Personal Access Token**（Settings → Developer settings → Tokens (classic) →
> 勾 `repo` 权限 → 生成 → 复制），在提示 `Password:` 时粘贴 token。
> 或者事先装好 GitHub CLI（`gh auth login`）会更省事。

### 3. 确认自动构建

推送后打开仓库的 **Actions** 页，应该能看到 `build` 工作流在跑；
跑完在 **Artifacts** 里能下载 `clouddisc-jar`。

---

## 二、以后的更新流程（每次 3 条命令）

```bash
# 1) 改代码 / 改文档后，先把版本号改掉
#    编辑 gradle.properties 里的 mod_version，例如 0.7.0 -> 0.7.1

# 2) 本地构建确认没问题
./gradlew build

# 3) 提交 + 打 tag + 推送
git add .
git commit -m "0.7.1: 修好 xxx"
git tag v0.7.1
git push
git push --tags
```

推 tag 后 CI 会**自动构建并创建一个 Release**（把 jar 挂上去）。
然后把 Release 链接发给朋友即可：

```
https://github.com/<你的用户名>/clouddisc/releases/latest
```

朋友点进去下载 jar → 丢进 `mods/` → 完事。

---

## 三、几个注意点

| 事项 | 说明 |
|---|---|
| **不要提交构建产物** | `.gitignore` 已经排除 `build/`；jar 走 Release 或 CI Artifacts |
| **不要提交 cookie / 令牌** | 你的 cookie、`X-Token`、服务器地址都只存在于客户端配置与你自己的服务器上；仓库里没有 |
| **不要提交反编译的 Minecraft 源码** | 版权问题（那些文件也**不在**本仓库目录里） |
| **服务端组件** | 给服主的和客户端是**同一个 jar**，Release 里那一个文件就够 |
| **MC 版本** | 仓库当前分支对应 1.20.1；换大版本时建议另开分支 |
| **万一误提交了敏感信息** | 立刻改密码/轮换 cookie，并用 `git filter-repo` 清理历史（或直接删仓库重建） |

---

## 四、README 里建议加的两样（可选）

1. 仓库首页的 **About** 里填一句简介与 Topics：`minecraft` `fabric` `netease` `music-disc`
2. 在 README 顶部加一张 Release 徽章，朋友一眼能看到最新版：

```markdown
[![最新版本](https://img.shields.io/github/v/release/<你的用户名>/clouddisc)](https://github.com/<你的用户名>/clouddisc/releases/latest)
```
