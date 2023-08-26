package info.nightscout.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.google.common.base.Joiner
import com.google.common.collect.Lists
import info.nightscout.database.impl.AppRepository
import info.nightscout.interfaces.Constants
import info.nightscout.rx.logging.LTag
import info.nightscout.shared.interfaces.ResourceHelper
import info.nightscout.database.entities.ValueWithUnit
import info.nightscout.database.entities.TemporaryTarget
import info.nightscout.database.entities.UserEntry.Action
import info.nightscout.database.entities.UserEntry.Sources
import info.nightscout.interfaces.GlucoseUnit
import info.nightscout.interfaces.protection.ProtectionCheck
import info.nightscout.interfaces.constraints.Constraint
import info.nightscout.interfaces.constraints.Constraints
import info.nightscout.interfaces.logging.UserEntryLogger
import info.nightscout.interfaces.profile.DefaultValueHelper
import info.nightscout.interfaces.profile.Profile
import info.nightscout.interfaces.profile.ProfileFunction
import info.nightscout.ui.databinding.DialogEnTemptargetBinding
import info.nightscout.core.ui.R
import info.nightscout.core.ui.dialogs.OKDialog
import info.nightscout.core.ui.toast.ToastUtils
import info.nightscout.database.ValueWrapper
import info.nightscout.database.impl.transactions.CancelCurrentTemporaryTargetIfAnyTransaction
import info.nightscout.database.impl.transactions.InsertAndCancelCurrentTemporaryTargetTransaction
import info.nightscout.interfaces.utils.HtmlHelper
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ENTempTargetDialog : DialogFragmentWithDate() {

    @Inject lateinit var constraintChecker: Constraints
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var defaultValueHelper: DefaultValueHelper
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var ctx: Context
    @Inject lateinit var protectionCheck: ProtectionCheck

    private lateinit var reasonList: List<String>

    private var queryingProtection = false
    private val disposable = CompositeDisposable()
    private var _binding: DialogEnTemptargetBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putDouble("duration", binding.duration.value)
        savedInstanceState.putDouble("tempTarget", binding.temptarget.value)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        onCreateViewGeneral()
        _binding = DialogEnTemptargetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val units = profileFunction.getUnits()
        binding.units.text = if (units == GlucoseUnit.MMOL) rh.gs(info.nightscout.core.ui.R.string.mmol) else rh.gs(R.string.mgdl)

        // set the Eating Now defaults
        val enTT = Profile.toCurrentUnits(units, profileFunction.getProfile()!!.getTargetMgdl())

        binding.duration.setParams(savedInstanceState?.getDouble("duration")
            ?: 0.0, 0.0, Constants.MAX_ENTT_DURATION, 10.0, DecimalFormat("0"), false, binding.okcancel.ok)

        if (profileFunction.getUnits() == GlucoseUnit.MMOL)
            binding.temptarget.setParams(
                savedInstanceState?.getDouble("tempTarget")
                    ?: enTT,
                Constants.MIN_TT_MMOL, enTT, 0.1, DecimalFormat("0.0"), false, binding.okcancel.ok)
        else
            binding.temptarget.setParams(
                savedInstanceState?.getDouble("tempTarget")
                    ?: enTT,
                Constants.MIN_TT_MGDL, Constants.MAX_TT_MGDL, 1.0, DecimalFormat("0"), false, binding.okcancel.ok)

        // temp target
        context?.let { context ->
            if (repository.getTemporaryTargetActiveAt(dateUtil.now()).blockingGet() is ValueWrapper.Existing)
                binding.targetCancel.visibility = View.VISIBLE
            else
                binding.targetCancel.visibility = View.GONE

            reasonList = Lists.newArrayList(
                rh.gs(R.string.manual),
                rh.gs(R.string.eatingsoon),
                rh.gs(R.string.eatingnow),
                rh.gs(R.string.activity),
                rh.gs(R.string.hypo)
            )
            binding.reasonList.setAdapter(ArrayAdapter(context, R.layout.spinner_centered, reasonList))

            binding.targetCancel.setOnClickListener { binding.duration.value = 0.0; shortClick(it) }
            binding.eatingSoon.setOnClickListener { shortClick(it) }
            binding.activity.setOnClickListener { shortClick(it) }
            binding.hypo.setOnClickListener { shortClick(it) }

            binding.eatingSoon.setOnLongClickListener {
                longClick(it)
                return@setOnLongClickListener true
            }
            binding.activity.setOnLongClickListener {
                longClick(it)
                return@setOnLongClickListener true
            }
            binding.hypo.setOnLongClickListener {
                longClick(it)
                return@setOnLongClickListener true
            }
            binding.durationLabel.labelFor = binding.duration.editTextId
            binding.temptargetLabel.labelFor = binding.temptarget.editTextId
        }

        // reset to Eating Now defaults
        //binding.temptarget.value = Profile.toCurrentUnits(units,profileFunction.getProfile()!!.getTargetMgdl())
        binding.duration.value = defaultValueHelper.determineEatingNowTTDuration().toDouble()
        binding.reasonList.setText(rh.gs(R.string.eatingnow), false)
    }

    private fun shortClick(v: View) {
        v.performLongClick()
        if (submit()) dismiss()
    }

    private fun longClick(v: View) {
        when (v.id) {
            info.nightscout.ui.R.id.eating_soon -> {
                binding.temptarget.value = defaultValueHelper.determineEatingSoonTT()
                binding.duration.value = defaultValueHelper.determineEatingSoonTTDuration().toDouble()
                binding.reasonList.setText(rh.gs(R.string.eatingsoon), false)
            }

            info.nightscout.ui.R.id.activity    -> {
                binding.temptarget.value = defaultValueHelper.determineActivityTT()
                binding.duration.value = defaultValueHelper.determineActivityTTDuration().toDouble()
                binding.reasonList.setText(rh.gs(R.string.activity), false)
            }

            info.nightscout.ui.R.id.hypo        -> {
                binding.temptarget.value = defaultValueHelper.determineHypoTT()
                binding.duration.value = defaultValueHelper.determineHypoTTDuration().toDouble()
                binding.reasonList.setText(rh.gs(R.string.hypo), false)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
        _binding = null
    }

    override fun submit(): Boolean {
        if (_binding == null) return false
        val actions: LinkedList<String> = LinkedList()
        var reason = binding.reasonList.text.toString()
        val unitResId = if (profileFunction.getUnits() == GlucoseUnit.MGDL) R.string.mgdl else R.string.mmol
        val target = binding.temptarget.value
        val duration = binding.duration.value.toInt()
        if (target != 0.0 && duration != 0) {
            actions.add(rh.gs(R.string.reason) + ": " + reason)
            actions.add(rh.gs(R.string.target_label) + ": " + Profile.toCurrentUnitsString(profileFunction, target) + " " + rh.gs(unitResId))
            actions.add(rh.gs(R.string.duration) + ": " + rh.gs(R.string.format_mins, duration))
        } else {
            actions.add(rh.gs(R.string.stoptemptarget))
            reason = rh.gs(R.string.stoptemptarget)
        }
        if (eventTimeChanged)
            actions.add(rh.gs(R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))

        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(R.string.temporary_target), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                val units = profileFunction.getUnits()
                when(reason) {
                    rh.gs(R.string.eatingnow)      -> uel.log(Action.TT, Sources.TTDialog, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged }, ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.EATING_NOW), ValueWithUnit.fromGlucoseUnit(target, units.asText), ValueWithUnit.Minute(duration))
                    rh.gs(R.string.eatingsoon)      -> uel.log(Action.TT, Sources.TTDialog, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged }, ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.EATING_SOON), ValueWithUnit.fromGlucoseUnit(target, units.asText), ValueWithUnit.Minute(duration))
                    rh.gs(R.string.activity)        -> uel.log(Action.TT, Sources.TTDialog, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged }, ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.ACTIVITY), ValueWithUnit.fromGlucoseUnit(target, units.asText), ValueWithUnit.Minute(duration))
                    rh.gs(R.string.hypo)            -> uel.log(Action.TT, Sources.TTDialog, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged }, ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.HYPOGLYCEMIA), ValueWithUnit.fromGlucoseUnit(target, units.asText), ValueWithUnit.Minute(duration))
                    rh.gs(R.string.manual)          -> uel.log(Action.TT, Sources.TTDialog, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged }, ValueWithUnit.TherapyEventTTReason(TemporaryTarget.Reason.CUSTOM), ValueWithUnit.fromGlucoseUnit(target, units.asText), ValueWithUnit.Minute(duration))
                    rh.gs(R.string.stoptemptarget) -> uel.log(Action.CANCEL_TT, Sources.TTDialog, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged })
                }
                if (target == 0.0 || duration == 0) {
                    disposable += repository.runTransactionForResult(CancelCurrentTemporaryTargetIfAnyTransaction(eventTime))
                        .subscribe({ result ->
                            result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                        }, {
                            aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
                        })
                } else {
                    disposable += repository.runTransactionForResult(InsertAndCancelCurrentTemporaryTargetTransaction(
                        timestamp = eventTime,
                        duration = TimeUnit.MINUTES.toMillis(duration.toLong()),
                        reason = when (reason) {
                            rh.gs(R.string.eatingnow) -> TemporaryTarget.Reason.EATING_NOW
                            rh.gs(R.string.eatingsoon) -> TemporaryTarget.Reason.EATING_SOON
                            rh.gs(R.string.activity)   -> TemporaryTarget.Reason.ACTIVITY
                            rh.gs(R.string.hypo)       -> TemporaryTarget.Reason.HYPOGLYCEMIA
                            else                            -> TemporaryTarget.Reason.CUSTOM
                        },
                        lowTarget = Profile.toMgdl(target, profileFunction.getUnits()),
                        highTarget = Profile.toMgdl(target, profileFunction.getUnits())
                    )
                    ).subscribe({ result ->
                        result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted temp target $it") }
                        result.updated.forEach { aapsLogger.debug(LTag.DATABASE, "Updated temp target $it") }
                    }, {
                        aapsLogger.error(LTag.DATABASE, "Error while saving temporary target", it)
                    })
                }

                if (duration == 10) sp.putBoolean(info.nightscout.core.utils.R.string.key_objectiveusetemptarget, true)
            })
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if(!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.name}")
                    ToastUtils.showToastInUiThread(ctx, rh.gs(info.nightscout.ui.R.string.dialog_canceled))
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}
