package com.winlator.cmod.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.winlator.cmod.ui.onboarding.ContainerStart
import com.winlator.cmod.ui.onboarding.OnboardingCreateScreen
import com.winlator.cmod.ui.settings.ContainersSettingsActivity
import com.winlator.cmod.ui.theme.WinZTheme

/** What the "+" of the container list opens: the same choice of ways to make a container as the first-run screen has. */
class NewContainerFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                OnboardingCreateScreen(
                    onChoose = { start ->
                        (activity as? ContainersSettingsActivity)?.startContainer(
                            when (start) {
                                ContainerStart.Windows -> "windows"
                                ContainerStart.Linux -> "linux"
                                ContainerStart.Game -> "game"
                                ContainerStart.Import -> "import"
                            }
                        )
                    },
                    onSettings = null,
                    onSkip = null,
                    title = "New container",
                    subtitle = "Pick how to start. A container is a separate environment for games, with its own settings.",
                    onBack = { parentFragmentManager.popBackStack() }
                )
            }
        }
    }
}
