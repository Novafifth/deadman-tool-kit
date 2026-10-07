package com.deadmantoolkit;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ServerUrlTest
{
	@Test
	public void normalInstallsAlwaysUseProduction()
	{
		assertEquals(DeadmanToolKitPlugin.PRODUCTION_SERVER_URL, DeadmanToolKitPlugin.serverUrl(false, null));
		assertEquals(DeadmanToolKitPlugin.PRODUCTION_SERVER_URL, DeadmanToolKitPlugin.serverUrl(false, "http://evil.example"));
	}

	@Test
	public void developerModeUsesLocalServerByDefault()
	{
		assertEquals(DeadmanToolKitPlugin.LOCAL_SERVER_URL, DeadmanToolKitPlugin.serverUrl(true, null));
		assertEquals(DeadmanToolKitPlugin.LOCAL_SERVER_URL, DeadmanToolKitPlugin.serverUrl(true, "  "));
		assertEquals(DeadmanToolKitPlugin.LOCAL_SERVER_URL, DeadmanToolKitPlugin.serverUrl(true, "not a url"));
	}

	@Test
	public void developerModeOverride()
	{
		assertEquals("https://staging.example", DeadmanToolKitPlugin.serverUrl(true, " https://staging.example "));
	}
}
