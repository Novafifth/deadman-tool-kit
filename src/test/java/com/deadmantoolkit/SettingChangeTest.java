package com.deadmantoolkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Which ConfigChanged events the plugin reacts to: only the settings, never its own profile saves. */
public class SettingChangeTest
{
	private static final String G = DeadmanToolKitConfig.GROUP;
	private static final String PROFILE = "rsprofile.abc";

	@Test
	public void settingsCount()
	{
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_CONNECTED));
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_SHARE));
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_LOCAL_LOG));
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_AUTO_OPEN));
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_BREACH));
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_STALE_HOURS));
		assertTrue(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_OFFER_NOTIFICATION));
	}

	@Test
	public void profileSavesInstallIdAndHintFlagDont()
	{
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, PROFILE, "slot0"));
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, PROFILE, "completed"));
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, PROFILE, "history"));
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, PROFILE, DeadmanToolKitConfig.KEY_CONNECTED));
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, null, "installId"));
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, null, InstallIdentity.KEY_INSTALL_TOKEN));
		assertFalse(DeadmanToolKitPlugin.isSettingChange(G, null, DeadmanToolKitConfig.KEY_HINT_SHOWN));
		assertFalse(DeadmanToolKitPlugin.isSettingChange("other-plugin", null, DeadmanToolKitConfig.KEY_CONNECTED));
	}
}
