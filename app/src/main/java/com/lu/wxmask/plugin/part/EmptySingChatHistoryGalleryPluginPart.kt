package com.lu.wxmask.plugin.part

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * 置空单聊页面菜单的“查找聊天记录”搜索结果
 * 
 * 8.0.76 全方位封死方案：
 * 1. 拦截“按日期查找”（com.tencent.mm.chatroom.ui.SelectDateUI）
 * 2. 拦截“表情历史”（com.tencent.mm.ui.chatting.gallery.EmojiHistoryListUI）
 * 3. 拦截“媒体历史”（MediaHistoryGalleryUI / MediaHistoryListUI）
 * 4. 拦截“查找聊天记录”统一多标签搜索页（FTSChattingConvMultiTabUI / FTSMultiAllResultFragment 等）
 * 
 * 只要命中配置的私密用户，无论进入哪个子页面（日期、表情、图片、文件），直接 finish 或清空结果！
 */
class EmptySingChatHistoryGalleryPluginPart : IPlugin {
    val MediaHistoryGalleryUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI"
    val MediaHistoryListUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryListUI"
    val EmojiHistoryListUI = "com.tencent.mm.ui.chatting.gallery.EmojiHistoryListUI"
    val SelectDateUI = "com.tencent.mm.chatroom.ui.SelectDateUI"

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        handleImageQueryMainUI(context, lpparam)
        setEmptyDetailHistoryUI(context, lpparam)
        setEmptyActionBarTabPageUI(context, lpparam)
    }

    private fun handleImageQueryMainUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val ImageQueryMainUI = ClazzN.from("com.tencent.mm.view.activity.ImageQueryMainUI") ?: return
        XposedHelpers2.findAndHookMethod(
            ImageQueryMainUI,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val act: Activity = param.thisObject as Activity
                    val userName = act.intent?.getStringExtra("Chat_User")
                        ?: act.intent?.getStringExtra("detail_username")
                    if (!userName.isNullOrBlank() && WXMaskPlugin.containChatUser(userName)) {
                        act.finish()
                    }
                }
            }
        )
    }

    private fun setEmptyDetailHistoryUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val activitiesToBlock = listOf(
            MediaHistoryGalleryUI,
            MediaHistoryListUI,
            EmojiHistoryListUI,
            SelectDateUI
        )

        activitiesToBlock.forEach { clazzName ->
            try {
                XposedHelpers2.findAndHookMethod(
                    clazzName,
                    context.classLoader,
                    "onCreate",
                    Bundle::class.java,
                    object : XC_MethodHook2() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!ConfigUtil.getOptionData().hideSingleSearch) {
                                return
                            }
                            val activity = param.thisObject as? Activity ?: return
                            val intent = activity.intent ?: return
                            val userName = intent.getStringExtra("kintent_talker")
                                ?: intent.getStringExtra("detail_username")
                                ?: intent.getStringExtra("Chat_User")
                                ?: intent.getStringExtra("RoomInfo_Id")

                            if (!userName.isNullOrBlank() && WXMaskPlugin.containChatUser(userName)) {
                                LogUtil.i("Block and finish $clazzName for masked user: $userName")
                                activity.finish()
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                LogUtil.w("Hook $clazzName onCreate error", e)
            }
        }
    }

    private fun setEmptyActionBarTabPageUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        // 针对 8.0.76，精准适配方法名为 s0(ArrayList)
        val hookMethodName = "s0"

        val fragmentsToHook = listOf(
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiAllResultFragment",
            "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiNormalResultFragment"
        )

        fragmentsToHook.forEach { fragClazz ->
            try {
                XposedHelpers2.findAndHookMethod(
                    fragClazz,
                    context.classLoader,
                    hookMethodName,
                    java.util.ArrayList::class.java,
                    object : XC_MethodHook2() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!ConfigUtil.getOptionData().hideSingleSearch) return
                            if (isHitMaskId(param.thisObject)) {
                                val arrayList = param.args[0] as? java.util.ArrayList<*>
                                arrayList?.clear()
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
            }
        }

        // tab==图片
        try {
            XposedHelpers2.findAndHookMethod(
                "com.tencent.mm.ui.chatting.search.multi.fragment.FTSMultiImageResultFragment",
                context.classLoader,
                "onCreateView",
                LayoutInflater::class.java,
                ViewGroup::class.java,
                Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!ConfigUtil.getOptionData().hideSingleSearch) return
                        if (isHitMaskId(param.thisObject)) {
                            val inflater = param.args[0] as LayoutInflater
                            val viewGroup: ViewGroup = param.args[1] as ViewGroup
                            val layoutId = XposedHelpers2.callMethod<Int>(param.thisObject, "getLayoutId")
                            param.result = inflater.inflate(layoutId, viewGroup, false)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
        }
    }

    private fun isHitMaskId(fragmentObj: Any?): Boolean {
        val activity = XposedHelpers2.callMethod<Activity>(fragmentObj, "getActivity") as? Activity ?: return false
        val intent = activity.intent ?: return false
        val username = intent.getStringExtra("detail_username")
            ?: intent.getStringExtra("kintent_talker")
            ?: intent.getStringExtra("Chat_User")
        return !username.isNullOrBlank() && WXMaskPlugin.containChatUser(username)
    }
}