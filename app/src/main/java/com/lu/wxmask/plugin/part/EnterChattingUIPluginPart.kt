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
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.QuickCountClickListenerUtil
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 聊天页页面处理（精准隔离版本）：
 * 
 * 核心设计：
 * 微信采用单例宿主复用机制：点进任何好友，都是同一个 MMChattingListView / ChattingUIFragment！
 * 1. 绝不盲目 hide！进入任何好友时，首先检查是否私密好友。
 *    - 若是私密好友，且未在当前被临时解锁 -> 盖上遮罩 / 设为 INVISIBLE
 *    - 若不是私密好友，或者私密好友已解锁 -> 强制设为 VISIBLE
 * 2. 借助 LauncherUI.startChatting(String, Bundle, boolean) 与 BaseConversationUI.startChatting
 *    在每次用户点击进入聊天时，精准记录当前的 targetUser，并重置上一位好友的临时解锁标记！
 */
class EnterChattingUIPluginPart() : IPlugin {

    companion object {
        const val TAG_MASK_VIEW = "chatting-onEnterBegin"
        var currentChattingUser: String? = null
        val unlockedUsers = HashSet<String>()
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        hookStartChatting(context)
        handleChattingUIFragment(context, lpparam)
    }

    /**
     * 监听点击会话进入聊天框的最上层入口：精准获知当前点进的是谁！
     */
    private fun hookStartChatting(context: Context) {
        val launcherUIClazz = XposedHelpers2.findClassIfExists("com.tencent.mm.ui.LauncherUI", context.classLoader)
        if (launcherUIClazz != null) {
            val startChattingMethods = XposedHelpers2.findMethodsByExactPredicate(launcherUIClazz) { m ->
                m.name == "startChatting" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java
            }
            startChattingMethods.forEach { method ->
                XposedHelpers2.hookMethod(
                    method,
                    object : XC_MethodHook2() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val targetUser = param.args[0] as? String
                            if (!targetUser.isNullOrEmpty()) {
                                if (currentChattingUser != targetUser) {
                                    // 切换了会话：清除所有临时解锁
                                    unlockedUsers.clear()
                                }
                                currentChattingUser = targetUser
                                LogUtil.i("startChatting target user: $targetUser")
                            }
                        }
                    }
                )
            }
        }
    }

    private fun handleChattingUIFragment(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val enterAction = EnterChattingHookAction(context, lpparam, TAG_MASK_VIEW)

        // onActivityCreated
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onActivityCreated",
                Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        enterAction.handle(param.thisObject)
                    }
                })
        }

        // onResume
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onResume",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        enterAction.handle(param.thisObject)
                    }
                }
            )
        }

        // onPause：离开当前聊天框时，清理临时解锁状态，确保下次返回必须重新解锁
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onPause",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        unlockedUsers.clear()
                    }
                }
            )
        }
    }
}

class EnterChattingHookAction(
    val context: Context,
    val lpparam: XC_LoadPackage.LoadPackageParam,
    val tagConst: String
) {
    fun handle(fragmentObj: Any) {
        val activity = ReflectUtil.invokeMethod(fragmentObj, "getActivity") as Activity? ?: return

        // 精准获取微信号：优先采用 startChatting 捕获的 currentChattingUser
        var chatUser: String? = EnterChattingUIPluginPart.currentChattingUser

        if (chatUser.isNullOrEmpty()) {
            try {
                val arguments = ReflectUtil.invokeMethod(fragmentObj, "getArguments") as Bundle?
                chatUser = arguments?.getString("Chat_User")
            } catch (e: Throwable) {
            }
        }

        if (chatUser.isNullOrEmpty()) {
            try {
                chatUser = activity.intent?.getStringExtra("Chat_User")
            } catch (e: Throwable) {
            }
        }

        LogUtil.i("enter chattingUI, current chatUser: $chatUser")

        // 核心分支：只有命中了私密名单才遮挡！
        if (!chatUser.isNullOrEmpty() && WXMaskPlugin.containChatUser(chatUser)) {
            if (EnterChattingUIPluginPart.unlockedUsers.contains(chatUser)) {
                // 已被本次临时解锁：放行
                showChatListUI(fragmentObj)
            } else {
                // 遮挡上锁
                hideChatListUI(fragmentObj, activity, chatUser)
            }
        } else {
            // 普通正常好友：必须无条件放行显示，绝不遮挡！
            showChatListUI(fragmentObj)
        }

        if (!chatUser.isNullOrEmpty()) {
            handleUserInputMagic(activity, fragmentObj, chatUser)
            handleShowAddMaskDialog(activity, fragmentObj, chatUser)
        }
    }

    private fun handleShowAddMaskDialog(activity: Activity, fragmentObj: Any, chatUser: String) {
        PluginProviders.from(WXConfigPlugin::class.java).checkShowAddMaskConfigDialog(fragmentObj)
    }

    private fun handleUserInputMagic(activity: Activity, fragmentObj: Any, chatUser: String) {
        val userInputView: EditText? = getUserChatEditText(fragmentObj) ?: return
        if (!ConfigUtil.getOptionData().enableChattingKey) return

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
                        .setMessage("是否移除wxid:$chatUser")
                        .setNegativeButton("确定") { _, _ ->
                            ConfigUtil.removeMaskItem(chatUser)
                            showChatListUI(fragmentObj)
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
            }?.firstOrNull() as? EditText
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
                if (MMListViewClazz != null) {
                    val mmListViewField = XposedHelpers2.findFirstFieldByExactType(fragmentObj.javaClass, MMListViewClazz)
                    mmListViewField.get(fragmentObj) as View
                } else null
            }.getOrNull()
        }
        return listView
    }

    private fun showChatListUI(fragmentObj: Any) {
        val chatListView: View? = findChatListView(fragmentObj)
        if (chatListView != null) {
            chatListView.visibility = View.VISIBLE
            QuickCountClickListenerUtil.unRegister(chatListView.parent as? View?)
        }
        showChatListUIFromMask(fragmentObj)
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
            ConfigUtil.getMaskList().first { it.maskId == chatUser }
        } catch (e: Exception) {
            return
        }

        val chatListView = findChatListView(fragmentObj)
        if (chatListView != null) {
            chatListView.visibility = View.INVISIBLE

            val quick = QuickTemporaryBean(ConfigUtil.getTemporaryJson() ?: JsonObject())
            QuickCountClickListenerUtil.register(chatListView.parent as? View?, quick.clickCount, quick.duration) {
                // 点击完成临时解锁
                chatListView.visibility = View.VISIBLE
                EnterChattingUIPluginPart.unlockedUsers.add(chatUser)
            }
            LogUtil.i("hide chatListView by setVisible for $chatUser")
        } else {
            hideListViewUIByMask(fragmentObj)
        }

        if (Constrant.CONFIG_TIP_MODE_ALERT == maskItem.tipMode) {
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