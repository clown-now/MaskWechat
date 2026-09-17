package com.lu.wxmask.plugin.part

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.addTextChangedListener
import com.google.gson.JsonObject
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.lposed.plugin.PluginProviders
import com.lu.magic.util.ReflectUtil
import com.lu.magic.util.ResUtil
import com.lu.magic.util.ToastUtil
import com.lu.magic.util.kxt.toElseEmptyString
import com.lu.magic.util.log.LogUtil
import com.lu.magic.util.view.ChildDeepCheck
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.bean.MaskItemBean
import com.lu.wxmask.bean.QuickTemporaryBean
import com.lu.wxmask.plugin.WXConfigPlugin
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ClipboardUtil
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.QuickCountClickListenerUtil
import com.lu.wxmask.util.ext.getViewId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 聊天页页面处理：
 * 1、隐藏单聊/群聊聊天记录
 * 2、支持退出聊天后再次进入重新自动上锁 (兼顾 onActivityCreated / onResume / onPause / onDestroy)
 */
class EnterChattingUIPluginPart() : IPlugin {
    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        handleChattingUIFragment(context, lpparam)
    }

    private fun handleChattingUIFragment(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val tagConst = "chatting-onEnterBegin"
        val enterAction = EnterChattingHookAction(context, lpparam, tagConst)

        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onActivityCreated",
                Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        super.afterHookedMethod(param)
                        LogUtil.d("hook onActivityCreated")
                        enterAction.handle(param)
                    }
                })
        }.onFailure {
            LogUtil.e("hook onActivityCreated error", it)
            return
        }

        // 每次进入聊天框（恢复可见）时，立即强制触发上锁逻辑
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onResume",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        super.afterHookedMethod(param)
                        LogUtil.d("hook onResume -> re-check and lock")
                        enterAction.handle(param)
                    }
                }
            )
        }.onFailure {
            LogUtil.e("hook onResume error", it)
        }

        // 退出或离开当前聊天框时，清理临时解锁状态，确保下次必须重新解锁
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onPause",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        super.afterHookedMethod(param)
                        enterAction.onExitChatting(param.thisObject)
                    }
                }
            )
        }.onFailure {
            LogUtil.e("hook onPause error", it)
        }
    }
}

class EnterChattingHookAction(
    val context: Context,
    val lpparam: XC_LoadPackage.LoadPackageParam,
    val tagConst: String
) {
    fun onExitChatting(fragmentObj: Any) {
        try {
            val chatListView: View? = findChatListView(fragmentObj)
            if (chatListView != null) {
                // 将列表重置为不可见，清除解锁标记
                chatListView.visibility = View.INVISIBLE
            }
        } catch (e: Throwable) {
        }
    }

    fun handle(param: XC_MethodHook.MethodHookParam) {
        val fragmentObj = param.thisObject
        LogUtil.w("enter chattingUI")
        val arguments = ReflectUtil.invokeMethod(fragmentObj, "getArguments") as Bundle?
        val activity = ReflectUtil.invokeMethod(fragmentObj, "getActivity") as Activity? ?: return

        if (arguments == null) {
            LogUtil.w("chattingUI's arguments is null")
            return
        }
        val chatUser = arguments.getString("Chat_User")
        if (chatUser == null || chatUser.isEmpty()) {
            return
        }

        // 命中配置的微信号
        if (WXMaskPlugin.containChatUser(chatUser)) {
            hideChatListUI(fragmentObj, activity, chatUser)
        } else {
            showChatListUI(fragmentObj)
        }

        handleUserInputMagic(activity, fragmentObj, chatUser)
        handleShowAddMaskDialog(activity, fragmentObj, chatUser)
    }

    private fun handleShowAddMaskDialog(activity: Activity, fragmentObj: Any, chatUser: String) {
        PluginProviders.from(WXConfigPlugin::class.java).checkShowAddMaskConfigDialog(fragmentObj)
    }

    private fun handleUserInputMagic(activity: Activity, fragmentObj: Any, chatUser: String) {
        val userInputView: EditText? = getUserChatEditText(fragmentObj)
        if (userInputView == null) {
            return
        }
        if (!ConfigUtil.getOptionData().enableChattingKey) {
            return
        }

        userInputView.addTextChangedListener {
            val editable = it ?: return@addTextChangedListener
            val text = editable.toElseEmptyString()
            when (text) {
                "#add" -> {
                    PluginProviders.from(WXConfigPlugin::class.java).showAddMaskDialog(userInputView.context, fragmentObj)
                    editable.clear()
                }

                "#del" -> {
                    AlertDialog.Builder(activity)
                        .setTitle("提示")
                        .setMessage("是否移除wxid:" + chatUser)
                        .setNegativeButton("确定") { _, _ ->
                            ConfigUtil.removeMaskItem(chatUser)
                            val chatListView: View? = findChatListView(fragmentObj)
                            if (chatListView != null) {
                                chatListView.visibility = View.INVISIBLE
                            }
                        }
                        .setNeutralButton("取消") { _, _ ->
                            editable.clear()
                        }
                        .show()
                    editable.clear()
                }

                "#clear" -> {
                    AlertDialog.Builder(activity)
                        .setTitle("提示")
                        .setMessage("是否清空所有配置")
                        .setNegativeButton("确定") { _, _ ->
                            ConfigUtil.clearData()
                        }
                        .setNeutralButton("取消") { _, _ ->
                            editable.clear()
                        }
                        .show()
                    editable.clear()
                }
            }
        }
    }

    private fun getUserChatEditText(fragmentObj: Any): EditText? {
        return XposedHelpers2.callMethod<View?>(fragmentObj, "findViewById", ResUtil.getViewId("bkk"))?.let {
            ChildDeepCheck().filter(it) { child ->
                child is EditText
            }?.firstOrNull()?.let {
                it as EditText
            }
        }
    }

    private fun findChatListView(fragmentObj: Any): View? {
        var listView = runCatching {
            ReflectUtil.invokeMethod(fragmentObj, "getListView") as View
        }.getOrNull()
        if (listView == null) {
            listView = runCatching {
                val mmListViewId =
                    if (AppVersionUtil.getVersionCode() < Constrant.WX_CODE_8_0_42) {
                        ResUtil.getViewId("b5n")
                    } else if (AppVersionUtil.getVersionCode() == Constrant.WX_CODE_PLAY_8_0_42) {
                        ResUtil.getViewId("bnu")
                    } else {
                        ResUtil.getViewId("bm6")
                    }
                XposedHelpers2.callMethod(fragmentObj, "findViewById", mmListViewId) as View
            }.getOrNull()
        }
        if (listView == null) {
            listView = runCatching {
                val MMListViewClazz = XposedHelpers2.findClassIfExists(
                    "com.tencent.mm.ui.chatting.view.MMChattingListView",
                    context.classLoader
                )
                if (MMListViewClazz == null) {
                    null
                } else {
                    val mmListViewField =
                        XposedHelpers2.findFirstFieldByExactType(fragmentObj.javaClass, MMListViewClazz)
                    val mmListView = mmListViewField.get(fragmentObj)
                    mmListView as View
                }
            }.getOrNull()
        }
        return listView
    }

    private fun showChatListUI(fragmentObj: Any) {
        val chatListView: View? = findChatListView(fragmentObj)
        if (chatListView != null) {
            chatListView.visibility = View.VISIBLE
            QuickCountClickListenerUtil.unRegister(chatListView.parent as? View?)
        } else {
            showChatListUIFromMask(fragmentObj)
        }
    }

    private fun showChatListUIFromMask(fragmentObj: Any) {
        val contentView = ReflectUtil.invokeMethod(fragmentObj, "getView") as? ViewGroup?
        val maskView = contentView?.findViewWithTag<View?>(tagConst)
        if (maskView != null) {
            (maskView.parent as? ViewGroup)?.removeView(maskView)
        }
    }

    private fun hideChatListUI(fragmentObj: Any, activity: Activity, chatUser: String) {
        val maskItem = try {
            ConfigUtil.getMaskList().first {
                it.maskId == chatUser
            }
        } catch (e: Exception) {
            LogUtil.w(e)
            return
        }

        val chatListView = findChatListView(fragmentObj)
        if (chatListView != null) {
            chatListView.visibility = View.INVISIBLE

            val quick = QuickTemporaryBean(ConfigUtil.getTemporaryJson() ?: JsonObject())
            QuickCountClickListenerUtil.register(chatListView.parent as? View?, quick.clickCount, quick.duration) {
                chatListView.visibility = View.VISIBLE
            }
            LogUtil.i("hide chatListView by setVisible")
        } else {
            hideListViewUIByMask(fragmentObj)
            LogUtil.i("hide chatListView by add Mask")
        }

        if (Constrant.WX_MASK_TIP_MODE_SILENT == maskItem.tipMode) {
            // 静默模式
        } else if (Constrant.CONFIG_TIP_MODE_ALERT == maskItem.tipMode) {
            handleAlertMode(activity, maskItem)
        }
    }

    private fun handleAlertMode(uiContext: Context, item: MaskItemBean) {
        AlertDialog.Builder(uiContext)
            .setTitle("提示")
            .setIcon(uiContext.applicationInfo.icon)
            .setMessage(MaskItemBean.TipData.from(item).mess)
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun hideListViewUIByMask(fragmentObj: Any) {
        val contentView = ReflectUtil.invokeMethod(fragmentObj, "getView") as? ViewGroup?
        contentView?.let {
            val pvId =
                if (AppVersionUtil.getVersionCode() == Constrant.WX_CODE_PLAY_8_0_42) {
                    ResUtil.getViewId("bm7")
                } else {
                    ResUtil.getViewId("b49")
                }
            val pv = it.findViewById<View?>(pvId)
            val vParent = (pv?.parent as? ViewGroup?) ?: it
            val maskView = vParent.findViewWithTag<View?>(tagConst)
            if (maskView != null) {
                (maskView.parent as? ViewGroup)?.removeView(maskView)
            }
            vParent.addView(
                View(it.context).apply {
                    tag = tagConst
                    background = ColorDrawable(0xFFEDEDED.toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            )
        }
    }
}