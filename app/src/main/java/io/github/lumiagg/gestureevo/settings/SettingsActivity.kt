package io.github.lumiagg.gestureevo.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Context.ACCESSIBILITY_SERVICE
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.transition.Fade
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityOptionsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivitySettingsBinding
import io.github.lumiagg.gestureevo.settings.ui.showAboutBottomSheet
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.PreferenceDataStoreAdapter
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSystemBars()
        applySystemBarInsets()
        setupSearchTransition()

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        binding.settingsSearchEntryCard.setOnClickListener {
            openSettingsSearch()
        }

    }

    private fun setupSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = DynamicColorUtils.surfaceContainerColor
    }

    private fun applySystemBarInsets() {
        val initialTop = binding.appBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                initialTop + systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.appBar)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (supportFragmentManager.findFragmentById(R.id.settings) as? SettingsFragment)
            ?.handlePendingNavigation()
    }

    private fun setupSearchTransition() {
        window.sharedElementExitTransition = SettingsSearchNavigator.buildSharedElementTransition(this)
        window.sharedElementReenterTransition = SettingsSearchNavigator.buildSharedElementTransition(this)
        window.exitTransition = Fade().apply { duration = 140L }
        window.reenterTransition = Fade().apply { duration = 140L }
    }

    private fun openSettingsSearch() {
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            this,
            binding.settingsSearchEntryCard,
            getString(R.string.settings_search_box_transition_name)
        )
        startActivity(Intent(this, SettingsSearchActivity::class.java), options.toBundle())
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var preferenceService: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore =
                PreferenceDataStoreAdapter(requireContext().settingsStore, lifecycleScope)
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<Preference>("preferenceSettingsGuide")?.setOnPreferenceClickListener {
                startActivity(Intent(requireActivity(), SettingsGuideActivity::class.java))
                true
            }

            findPreference<Preference>("preferenceConfigTrigger")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            FreeTriggerSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceActionView")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            ActionViewSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceAppearance")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            AppearanceSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceSearch")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            SearchEngineSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceFreeform")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            FreeFormSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceAbout")?.apply {
                setOnPreferenceClickListener {
                    showAboutBottomSheet(requireActivity() as AppCompatActivity)
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceData")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), DataBackActivity::class.java))
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceExternalAction")?.apply {
                setOnPreferenceClickListener {
                    startActivity(Intent(requireActivity(), ExternalActionSettingsActivity::class.java))
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceInspire")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            InspireSettingsActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            findPreference<Preference>("preferenceBackgroundStability")?.apply {
                setOnPreferenceClickListener {
                    startActivity(
                        Intent(
                            requireActivity(),
                            BackgroundStabilityActivity::class.java
                        )
                    )
                    return@setOnPreferenceClickListener true
                }
            }

            preferenceService = findPreference<Preference>("preferenceService")?.apply {
                updateAccessibilityServiceStatus(this)
                setOnPreferenceClickListener {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    return@setOnPreferenceClickListener true
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceService?.let { updateAccessibilityServiceStatus(it) }
            handlePendingNavigation()
        }

        fun handlePendingNavigation() {
            val targetPreferenceKey = activity?.intent
                ?.getStringExtra(SettingsSearchNavigator.EXTRA_TARGET_PREFERENCE_KEY)
                ?: return
            listView?.post {
                scrollToPreference(targetPreferenceKey)
                activity?.intent?.removeExtra(SettingsSearchNavigator.EXTRA_TARGET_PREFERENCE_KEY)
            }
        }

        private fun updateAccessibilityServiceStatus(preference: Preference) {
            lifecycleScope.launch(Dispatchers.IO) {
                autoOpenAccessibilityService(requireContext())
                val title = if (evoAccessibilityServiceIsEnable(requireContext())) {
                    getString(R.string.settings_accessibility_running)
                } else {
                    getString(R.string.settings_accessibility_off)
                }
                withContext(Dispatchers.Main) { preference.title = title }
            }
        }
    }
}

fun evoAccessibilityServiceIsEnable(context: Context): Boolean {
    val accessibilityManager =
        (context.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager)
    val accessibilityServices = accessibilityManager.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )
    for (info in accessibilityServices) {
        if (info.id.contains(context.packageName)) {
            return true
        }
    }
    return false
}

suspend fun autoOpenAccessibilityService(context: Context): Boolean =
    withContext(Dispatchers.Default) {
        if (evoAccessibilityServiceIsEnable(context)) {
            return@withContext true
        } else {
            try {
                Log.d("SettingsActivity", "autoOpenAccessibilityService: ")
                val enabledService = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    "$enabledService:${context.packageName}/${context.packageName}.gesture.GestureService"
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1
                )
                delay(300)
                return@withContext true
            } catch (e: SecurityException) {
                return@withContext false
            }
        }
    }
