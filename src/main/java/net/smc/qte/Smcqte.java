package net.smc.qte; // 修改为你实际的包名

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Smcqte implements ModInitializer {
	public static final String MOD_ID = "smc-qte";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 这里留空即可，主要逻辑在客户端初始化里
		LOGGER.info("AutoFish Mod Initialized!");
	}
}