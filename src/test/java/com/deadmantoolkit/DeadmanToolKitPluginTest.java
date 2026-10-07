package com.deadmantoolkit;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DeadmanToolKitPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DeadmanToolKitPlugin.class);
		RuneLite.main(args);
	}
}
