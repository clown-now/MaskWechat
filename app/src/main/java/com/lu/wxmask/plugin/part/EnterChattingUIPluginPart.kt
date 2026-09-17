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
import com.lu.wxmask.util.ext.getViewId
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 聊天页页面处理（精准闭环版本）：
 * 
 * 微信8.0.76 单例架构下的核心生命周期拦截点：
 * 1. startChatting：点击会话进入聊天框的一瞬间被调用 -> 记录 currentChattingUser，重置临时解锁
 * 2. closeChatting：按返回键、侧滑退出、关闭聊天框的一瞬间被调用 -> 清空 currentChattingUser，清空解锁标记
 * 3. BaseChattingUIFragment 的 onEnterBegin / onActivityCreated / onResume：
 *    - 无论微信何时重绘，只要 currentChattingUser 属于私密好友，且未在当前被临时解锁 -> 立即遮挡
 *    - 只要不是私密好友，或者已被临时解锁 -> 绝对放行，绝不误遮
 */
class EnterChattingUIPluginPart() : IPlugin {

    companion object {
        const val TAG_MASK_VIEW = "chatting-onEnterBegin"
        @Volatile
        var currentChattingUser: String? = null
        val unlockedUsers = HashSet<String>()
        var cachedFragment: Any? = null
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        hookStartChatting(context, lpparam)
        hookCloseChatting(context)
        handleChattingUIFragment(context, lpparam)
    }

    /**
     * 1. 监听打开聊天框入口（startChatting）
     */
    private fun hookStartChatting(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
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
                                    unlockedUsers.clear()
                                }
                                currentChattingUser = targetUser
                                LogUtil.i("startChatting -> current target user: $targetUser")
                                
                                cachedFragment?.let { frag ->
                                    EnterChattingHookAction(context, lpparam, TAG_MASK_VIEW).handle(frag)
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * 2. 监听退出聊天框入口（closeChatting）
     * 无论按左上角返回、按系统返回键、还是侧滑返回，微信必走 closeChatting！
     */
    private fun hookCloseChatting(context: Context) {
        val launcherUIClazz = XposedHelpers2.findClassIfExists("com.tencent.mm.ui.LauncherUI", context.classLoader)
        if (launcherUIClazz != null) {
            val closeChattingMethods = XposedHelpers2.findMethodsByExactPredicate(launcherUIClazz) { m ->
                m.name == "closeChatting"
            }
            closeChattingMethods.forEach { method ->
                XposedHelpers2.hookMethod(
                    method,
                    object : XC_MethodHook2() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            LogUtil.i("closeChatting called -> exit conversation, reset all unlock states")
                            unlockedUsers.clear()
                            currentChattingUser = null
                        }
                    }
                )
            }
        }
    }

    private fun handleChattingUIFragment(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val enterAction = EnterChattingHookAction(context, lpparam, TAG_MASK_VIEW)

        // 尝试 Hook 微信进入聊天框的核心回调 onEnterBegin（比 onResume 更加可靠，从主页拉出聊天框必走此方法！）
        runCatching {
            val baseFragmentClass = ClazzN.from(ClazzN.BaseChattingUIFragment, context.classLoader)
            val onEnterBeginMethod = XposedHelpers2.findMethodExactIfExists(baseFragmentClass, "onEnterBegin")
            if (onEnterBeginMethod != null) {
                XposedHelpers2.hookMethod(
                    onEnterBeginMethod,
                    object : XC_MethodHook2() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            cachedFragment = param.thisObject
                            enterAction.handle(param.thisObject)
                        }
                    }
                )
                LogUtil.i("Successfully hooked onEnterBegin in BaseChattingUIFragment")
            }
        }

        // onActivityCreated
        runCatching {
            XposedHelpers2.findAndHookMethod(
                ClazzN.BaseChattingUIFragment,
                context.classLoader,
                "onActivityCreated",
                Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cachedFragment = param.thisObject
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
                        cachedFragment = param.thisObject
                        enterAction.handle(param.thisObject)
                    }
                }
            )
        }

        // onPause
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

        LogUtil.i("enter chattingUI action handle, user: $chatUser")

        if (!chatUser.isNullOrEmpty() && WXMaskPlugin.containChatUser(chatUser)) {
            if (EnterChattingUIPluginPart.unlockedUsers.contains(chatUser)) {
                showChatListUI(fragmentObj)
            } else {
                hideChatListUI(fragmentObj, activity, chatUser)
            }
        } else {
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
        val userInputView: EditText = getUserChatEditText(fragmentObj) ?: return
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