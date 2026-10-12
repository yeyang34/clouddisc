package dev.clouddisc;

/**
 * 本机构建用的「预设解析服务」。
 *
 * <p><b>仓库里这个文件是空值</b>（{@code ENDPOINT = ""}、{@code TOKEN = ""}），
 * 因此公开源码与 CI 构建出的 jar <b>不含任何服务器信息</b>。
 * 只有作者本机把它填上真实值（并用
 * {@code git update-index --skip-worktree src/main/java/dev/clouddisc/PresetResolver.java}
 * 让 git 忽略这处本地修改），构建出的 jar 才会"开箱即用"。
 *
 * <p>这样安排的意义：
 * <ul>
 *   <li>朋友拿到 jar 直接能放 VIP 歌，不需要手动配任何东西；</li>
 *   <li>仓库（含 git 历史与 CI 产物）里始终没有地址与令牌；</li>
 *   <li>使用者仍可在配置界面覆盖 —— 例如令牌轮换后，把新的地址/令牌填进配置即可，
 *       不必换 jar（配置里的值优先于预设值）。</li>
 * </ul>
 *
 * <p>⚠️ 不要把这个文件填好后再提交：提交前请确认
 * {@code git status} 里看不到它（skip-worktree 已生效）。
 */
public final class PresetResolver {
	/** 预设的解析服务地址（{@code {id}} 占位）。留空表示不预设。 */
	public static final String ENDPOINT = "";

	/** 预设的访问令牌，会作为 {@code X-Token} 请求头发送。留空表示不发送。 */
	public static final String TOKEN = "";

	/** 预设值是否可用。 */
	public static boolean present() {
		return ENDPOINT != null && !ENDPOINT.isBlank();
	}

	private PresetResolver() {
	}
}
