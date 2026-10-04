package fi.dy.masa.litematica.compat.voxy;

import fi.dy.masa.malilib.MaLiLibFabricData;

import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;

public class VoxyCompat
{
	// malilib for 1.21.11 has no ModIds.voxy
	private static final String VOXY_MOD_ID = "voxy";
	private static boolean isVoxyLoaded = false;
	private static String voxyVersion = "";

	public static void register()
	{
		if (MaLiLibFabricData.ALL_MOD_VERSIONS.containsKey(VOXY_MOD_ID))
		{
			voxyVersion = MaLiLibFabricData.ALL_MOD_VERSIONS.get(VOXY_MOD_ID);
			isVoxyLoaded = true;
		}

		Litematica.LOGGER.info("Voxy: [{}]", isVoxyLoaded ? voxyVersion : "N/F");
	}

	public static boolean hasVoxy()
	{
		return isVoxyLoaded;
	}

	public static void checkConfig()
	{
		Configs.Generic.LOAD_ENTIRE_SCHEMATICS.setBooleanValue(hasVoxy());
	}
}
