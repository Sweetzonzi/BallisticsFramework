package io.github.sweetzonzi.terminal_ballistics;

import com.mojang.logging.LogUtils;
import io.github.sweetzonzi.terminal_ballistics.api.TBDamageExtensions;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(TerminalBallistics.MOD_ID)
public class TerminalBallistics {

    public static final String MOD_ID = "terminal_ballistics";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TerminalBallistics() {
        TBDamageExtensions.init();
    }
}
