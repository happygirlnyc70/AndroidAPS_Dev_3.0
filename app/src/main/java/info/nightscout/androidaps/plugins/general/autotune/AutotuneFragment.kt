package info.nightscout.androidaps.plugins.general.autotune

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.databinding.AutotuneFragmentBinding
import info.nightscout.androidaps.dialogs.ProfileViewerDialog
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.autotune.data.ATProfile
import info.nightscout.androidaps.plugins.general.autotune.events.EventAutotuneUpdateGui
import info.nightscout.androidaps.plugins.general.autotune.events.EventAutotuneUpdateResult
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin
import info.nightscout.androidaps.plugins.profile.local.LocalProfilePlugin
import info.nightscout.androidaps.plugins.profile.local.events.EventLocalProfileChanged
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.MidnightTime
import info.nightscout.androidaps.utils.alertDialogs.OKDialog.showConfirmation
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject

/**
 * Created by Rumen Georgiev on 1/29/2018.
 * Deep rework by philoul on 06/2020
 */
// Todo: Reset results field and Switch/Copy button visibility when Nb of selected days is changed
class AutotuneFragment : DaggerFragment() {
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var autotunePlugin: AutotunePlugin
    @Inject lateinit var sp: SP
    @Inject lateinit var iobCobCalculatorPlugin: IobCobCalculatorPlugin
    @Inject lateinit var treatmentsPlugin: TreatmentsPlugin
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var localProfilePlugin: LocalProfilePlugin
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rxBus: RxBusWrapper

    private var disposable: CompositeDisposable = CompositeDisposable()
    private var lastRun: Date? = null
    private var lastRunTxt: String? = null
    private var tempResult = ""
    private val log = LoggerFactory.getLogger(AutotunePlugin::class.java)
    private var _binding: AutotuneFragmentBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = AutotuneFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.autotuneRun.setOnClickListener {
            val daysBack = binding.tuneDays.text.toString().toInt()
            if (daysBack > 0) {
                tempResult = ""
                AutotunePlugin.calculationRunning = true
                Thread(Runnable {
                    autotunePlugin.aapsAutotune(daysBack, false)
                }).start()
                lastRunTxt = if (AutotunePlugin.lastRun != null) "" + dateUtil.dateAndTimeString(AutotunePlugin.lastRun) else ""
                lastRun = if (AutotunePlugin.lastRun != null) AutotunePlugin.lastRun else Date(0)
                updateGui()
            } else binding.tuneResult.text = resourceHelper.gs(R.string.autotune_min_days)
        }

        binding.autotuneCopylocal.setOnClickListener {
            val localName = resourceHelper.gs(R.string.autotune_tunedprofile_name) + " " + dateUtil.dateAndTimeString(AutotunePlugin.lastRun)
            showConfirmation(requireContext(), resourceHelper.gs(R.string.autotune_copy_localprofile_button), resourceHelper.gs(R.string.autotune_copy_local_profile_message) + "\n" + localName + " " + dateUtil.dateAndTimeString(lastRun),
                Runnable {
                    localProfilePlugin.addProfile(localProfilePlugin.copyFrom(AutotunePlugin.tunedProfile!!.getProfile(), localName))
                    rxBus.send(EventLocalProfileChanged())
                    AutotunePlugin.copyButtonVisibility = View.GONE
                    updateGui()
                })
        }

        binding.autotuneCompare.setOnClickListener {
            val currentprofile = AutotunePlugin.currentprofile
            //log("profile : " + currentprofile?.profilename + "\n" + currentprofile?.data.toString())
            val tunedprofile = AutotunePlugin.tunedProfile
            //log("tunedprofile : " + AutotunePlugin.tunedProfile?.profilename + "\n" + tunedprofile?.data.toString())
            ProfileViewerDialog().also { pvd ->
                pvd.arguments = Bundle().also {
                    it.putLong("time", DateUtil.now())
                    it.putInt("mode", ProfileViewerDialog.Mode.PROFILE_COMPARE.ordinal)
                    it.putString("customProfile", currentprofile?.getData(true).toString())
                    it.putString("customProfile2", tunedprofile?.data.toString())
                    it.putString("customProfileUnits", profileFunction.getUnits())
                    it.putString("customProfileName", currentprofile?.profilename + "\n" + AutotunePlugin.tunedProfile?.profilename)
                }
            }.show(childFragmentManager, "ProfileViewDialog")
        }

        binding.autotuneProfileswitch.setOnClickListener{
            //val name = resourceHelper.gs(R.string.autotune_tunedprofile_name)
            val profileStore = AutotunePlugin.tunedProfile!!.profileStore
            log("ProfileSwitch pressed")
            if (profileStore != null) {
                showConfirmation(requireContext(), resourceHelper.gs(R.string.activate_profile) + ": " + AutotunePlugin.tunedProfile!!.profilename + " ?", Runnable {
                    activePlugin.activeTreatments.doProfileSwitch(AutotunePlugin.tunedProfile!!.profileStore, AutotunePlugin.tunedProfile!!.profilename, 0, 100, 0, DateUtil.now())
                    rxBus.send(EventLocalProfileChanged())
                    AutotunePlugin.profileSwitchButtonVisibility = View.GONE
                    updateGui()
                })
            } else log("ProfileStore is null!")
        }

        lastRun = if (AutotunePlugin.lastRun != null) AutotunePlugin.lastRun else Date(0)
        if (lastRun!!.time > MidnightTime.calc(System.currentTimeMillis() - AutotunePlugin.autotuneStartHour * 3600 * 1000L) + AutotunePlugin.autotuneStartHour * 3600 * 1000L && AutotunePlugin.result !== "") {
            binding.tuneWarning.text = resourceHelper.gs(R.string.autotune_warning_after_run)
            binding.tuneDays.setText(AutotunePlugin.lastNbDays)
        } else { //if new day reinit result, default days, warning and button's visibility
            binding.tuneWarning.text = addWarnings()
            binding.tuneDays.setText(sp.getString(R.string.key_autotune_default_tune_days, "5"))
            AutotunePlugin.result = ""
            AutotunePlugin.tunedProfile = null
            AutotunePlugin.profileSwitchButtonVisibility = View.GONE
            AutotunePlugin.copyButtonVisibility = View.GONE
            binding.autotuneCompare.visibility = View.GONE
        }
        lastRunTxt = if (AutotunePlugin.lastRun != null) dateUtil.dateAndTimeString(AutotunePlugin.lastRun) else ""
        updateGui()
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable.add(rxBus
            .toObservable(EventAutotuneUpdateGui::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                updateGui()
            }, { fabricPrivacy.logException(it) })
        )
        disposable.add(rxBus
            .toObservable(EventAutotuneUpdateResult::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                tempResult = it.result
                updateGui()
            }, { fabricPrivacy.logException(it) })
        )
        updateGui()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    @Synchronized
    private fun updateGui() {
        if (AutotunePlugin.calculationRunning) {
            binding.autotuneRun.visibility = View.GONE
            binding.autotuneCompare.visibility = View.GONE
            binding.tuneWarning.text = resourceHelper.gs(R.string.autotune_warning_during_run)
            binding.tuneResult.text = AutotunePlugin.result
        } else if (AutotunePlugin.lastRunSuccess) {
            binding.autotuneRun.visibility = View.VISIBLE
            binding.tuneWarning.text = resourceHelper.gs(R.string.autotune_warning_after_run)
            binding.tuneResult.text = AutotunePlugin.result
            binding.autotuneCompare.visibility = View.VISIBLE
        } else {
            binding.tuneResult.text = AutotunePlugin.result
            binding.autotuneRun.visibility = View.VISIBLE
        }
        if (AutotunePlugin.tunedProfile == null || AutotunePlugin.currentprofile == null)
            binding.autotuneCompare.visibility = View.GONE
        binding.autotuneCopylocal.visibility = AutotunePlugin.copyButtonVisibility
        binding.autotuneProfileswitch.visibility = AutotunePlugin.profileSwitchButtonVisibility
        binding.tuneLastrun.text = lastRunTxt
    }

    private fun addWarnings(): String {
        var warning = resourceHelper.gs(R.string.autotune_warning_before_run)
        var nl = "\n"
        val profile = ATProfile(profileFunction.getProfile(System.currentTimeMillis()))
        if (!profile.isValid) return resourceHelper.gs(R.string.autotune_profile_invalid)
        if (profile.icSize > 1) {
            //warning = nl + "Autotune works with only one IC value, your profile has " + profile.getIcSize() + " values. Average value is " + profile.ic + "g/U";
            warning = nl + resourceHelper.gs(R.string.format_autotune_ic_warning, profile.icSize, profile.ic)
            nl = "\n"
        }
        if (profile.isfSize > 1) {
            //warning = nl + "Autotune works with only one ISF value, your profile has " + profile.getIsfSize() + " values. Average value is " + profile.isf/toMgDl + profileFunction.getUnits() + "/U";
            warning = nl + resourceHelper.gs(R.string.format_autotune_isf_warning, profile.isfSize, Profile.fromMgdlToUnits(profile.isf, profileFunction.getUnits()), profileFunction.getUnits())
        }
        return warning
    }

    private fun log(message: String) {
        autotunePlugin.atLog("[Fragment] $message")
    }
}