package app.aaps.plugins.automation.actions

import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.T
import app.aaps.core.main.extensions.fromConstant
import app.aaps.core.utils.JsonHelper
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.ValueWithUnit
import app.aaps.database.impl.AppRepository
import app.aaps.database.impl.transactions.InsertIfNewByTimestampTherapyEventTransaction
import app.aaps.plugins.automation.elements.InputCarePortalMenu
import app.aaps.plugins.automation.elements.InputDuration
import app.aaps.plugins.automation.elements.InputString
import app.aaps.plugins.automation.elements.LabelWithElement
import app.aaps.plugins.automation.elements.LayoutBuilder
import dagger.android.HasAndroidInjector
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONObject
import javax.inject.Inject

class ActionCarePortalEvent(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var repository: AppRepository
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var sp: SP
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var uel: UserEntryLogger

    private val disposable = CompositeDisposable()

    var note = InputString()
    var duration = InputDuration(0, InputDuration.TimeUnit.MINUTES)
    var cpEvent = InputCarePortalMenu(rh)
    private var valuesWithUnit = mutableListOf<ValueWithUnit?>()

    private constructor(injector: HasAndroidInjector, actionCPEvent: ActionCarePortalEvent) : this(injector) {
        cpEvent = InputCarePortalMenu(rh, actionCPEvent.cpEvent.value)
    }

    override fun friendlyName(): Int = app.aaps.core.ui.R.string.careportal
    override fun shortDescription(): String = rh.gs(cpEvent.value.stringResWithValue, note.value)

    @DrawableRes override fun icon(): Int = cpEvent.value.drawableRes

    override fun doAction(callback: Callback) {
        val enteredBy = sp.getString("careportal_enteredby", "AAPS")
        val eventTime = dateUtil.now()
        val therapyEvent = TherapyEvent(
            timestamp = eventTime,
            type = cpEvent.value.therapyEventType,
            glucoseUnit = TherapyEvent.GlucoseUnit.fromConstant(profileFunction.getUnits())
        )
        valuesWithUnit.add(ValueWithUnit.TherapyEventType(therapyEvent.type))

        therapyEvent.enteredBy = enteredBy
        if (therapyEvent.type == TherapyEvent.Type.QUESTION || therapyEvent.type == TherapyEvent.Type.ANNOUNCEMENT) {
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            if (glucoseStatus != null) {
                therapyEvent.glucose = glucoseStatus.glucose
                therapyEvent.glucoseType = TherapyEvent.MeterType.SENSOR
                valuesWithUnit.add(ValueWithUnit.Mgdl(glucoseStatus.glucose))
                valuesWithUnit.add(ValueWithUnit.TherapyEventMeterType(TherapyEvent.MeterType.SENSOR))
            }
        } else {
            therapyEvent.duration = T.mins(duration.value.toLong()).msecs()
            valuesWithUnit.add(ValueWithUnit.Minute(duration.value).takeIf { duration.value != 0 })
        }
        therapyEvent.note = note.value
        valuesWithUnit.add(ValueWithUnit.SimpleString(note.value).takeIf { note.value.isNotBlank() })
        disposable += repository.runTransactionForResult(InsertIfNewByTimestampTherapyEventTransaction(therapyEvent))
            .subscribe(
                { result -> result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted therapy event $it") } },
                { aapsLogger.error(LTag.DATABASE, "Error while saving therapy event", it) }
            )
        uel.log(UserEntry.Action.CAREPORTAL, UserEntry.Sources.Automation, title, valuesWithUnit)
    }

    override fun toJSON(): String {
        val data = JSONObject()
            .put("cpEvent", cpEvent.value)
            .put("note", note.value)
            .put("durationInMinutes", duration.value)
        return JSONObject()
            .put("type", this.javaClass.simpleName)
            .put("data", data)
            .toString()
    }

    override fun fromJSON(data: String): Action {
        val o = JSONObject(data)
        cpEvent.value = InputCarePortalMenu.EventType.valueOf(JsonHelper.safeGetString(o, "cpEvent")!!)
        note.value = JsonHelper.safeGetString(o, "note", "")
        duration.value = JsonHelper.safeGetInt(o, "durationInMinutes")
        return this
    }

    override fun hasDialog(): Boolean = true

    override fun generateDialog(root: LinearLayout) {
        LayoutBuilder()
            .add(cpEvent)
            .add(LabelWithElement(rh, rh.gs(app.aaps.core.ui.R.string.duration_min_label), "", duration))
            .add(LabelWithElement(rh, rh.gs(app.aaps.core.ui.R.string.notes_label), "", note))
            .build(root)
    }

    override fun isValid(): Boolean = true
}
