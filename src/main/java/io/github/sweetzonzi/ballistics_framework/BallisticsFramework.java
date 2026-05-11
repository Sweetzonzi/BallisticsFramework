package io.github.sweetzonzi.ballistics_framework;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.ballistics_framework.api.BFDamageExtensions;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(BallisticsFramework.MOD_ID)
public class BallisticsFramework {

    public static final String MOD_ID = "ballistics_framework";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BallisticsFramework() {
        BFDamageExtensions.init();
    }
}
