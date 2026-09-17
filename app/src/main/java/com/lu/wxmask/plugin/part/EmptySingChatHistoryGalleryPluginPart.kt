package com.lu.wxmask.plugin.part

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.ReflectUtil
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.ClazzN
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.ConfigUtil
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 单聊页面“查找聊天记录”（日期、图片/视频、文件、链接、多标签搜索）智能处理：
 * 
 * 规则：
 * 1. 只有未解锁状态下，进入查询页面才拦截显示“无结果”；
 * 2. 一旦用户在聊天界面点击完成了临时解锁（EnterChattingUIPluginPart.unlockedUsers 包含当前好友），
 *    则所有查询功能（按日期查找、图片/视频、文件、聊天记录搜索等）完全放行，正常展示真实数据！
 * 3. 未解锁状态下：
 *    - 允许正常打开日期选择界面（SelectDateUI），但不加载任何历史消息日期点（日历全灰/不可点选，提示无记录）
 *    - 允许打开多媒体历史界面（MediaHistoryGalleryUI / MediaHistoryListUI），数据置空展示“无数据”
 *    - 多标签搜索结果（FTSMultiAllResultFragment 等）清空列表，展示“无结果”
 */
class EmptySingChatHistoryGalleryPluginPart : IPlugin {
    val MediaHistoryGalleryUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryGalleryUI"
    val MediaHistoryListUI = "com.tencent.mm.ui.chatting.gallery.MediaHistoryListUI"
    val EmojiHistoryListUI = "com.tencent.mm.ui.chatting.gallery.EmojiHistoryListUI"
    val SelectDateUI = "com.tencent.mm.chatroom.ui.SelectDateUI"

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        handleImageQueryMainUI(context, lpparam)
        handleSelectDateUI(context, lpparam)
        handleMediaHistoryUI(context, lpparam)
        setEmptyActionBarTabPageUI(context, lpparam)
    }

    /**
     * 判断当前操作的用户是否处于“锁定”状态（是私密好友，且未在当前被临时解锁）
     */
    private fun isUserLocked(userName: String?): Boolean {
        if (userName.isNullOrBlank()) return false
        if (!WXMaskPlugin.containChatUser(userName)) return false
        // 如果当前好友已被临时解锁，则放行
        if (EnterChattingUIPluginPart.unlockedUsers.contains(userName)) {
            LogUtil.i("User $userName is currently temporary unlocked, allow querying chat history")
            return false
        }
        return true
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
                    if (isUserLocked(userName)) {
                        // 未解锁：清空数据或 finish
                        act.finish()
                    }
                }
            }
        )
    }

    /**
     * 1. 拦截“按日期查找聊天记录”
     * 可以点进去查看日历，但如果未解锁，不向数据库查询可点击日期（数据全空，显示无记录）；解锁后正常加载！
     */
    private fun handleSelectDateUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        try {
            XposedHelpers2.findAndHookMethod(
                SelectDateUI,
                context.classLoader,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = activity.intent ?: return
                        val userName = intent.getStringExtra("detail_username")
                            ?: intent.getStringExtra("kintent_talker")
                            ?: intent.getStringExtra("Chat_User")

                        if (isUserLocked(userName)) {
                            // 未解锁：清空已准备的日期 Map，并显示空提示
                            runCatching {
                                val dateMap = XposedHelpers2.getObjectField(activity, "f") as? java.util.HashMap<*, *>
                                dateMap?.clear()
                                // 隐藏日历主体，或者将无记录文本设为可见
                                val emptyTv = XposedHelpers2.getObjectField(activity, "m") as? TextView
                                emptyTv?.visibility = View.VISIBLE
                                emptyTv?.text = "无聊天记录"
                                
                                val dayPickerView = XposedHelpers2.getObjectField(activity, "d") as? View
                                dayPickerView?.visibility = View.GONE
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            LogUtil.w("Hook SelectDateUI error", e)
        }
    }

    /**
     * 2. 拦截图片/视频、文件等媒体历史（MediaHistoryGalleryUI / MediaHistoryListUI）
     */
    private fun handleMediaHistoryUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
        val activities = listOf(
            MediaHistoryGalleryUI,
            MediaHistoryListUI,
            EmojiHistoryListUI
        )

        activities.forEach { clazzName ->
            try {
                XposedHelpers2.findAndHookMethod(
                    clazzName,
                    context.classLoader,
                    "onCreate",
                    Bundle::class.java,
                    object : XC_MethodHook2() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val intent = activity.intent ?: return
                            val userName = intent.getStringExtra("kintent_talker")
                                ?: intent.getStringExtra("detail_username")
                                ?: intent.getStringExtra("Chat_User")
                                ?: intent.getStringExtra("RoomInfo_Id")

                            if (isUserLocked(userName)) {
                                // 未解锁状态：隐藏 RecyclerView 列表，展示空数据提示
                                runCatching {
                                    val recyclerView = (XposedHelpers2.getObjectField(activity, "f") as? View)
                                        ?: (XposedHelpers2.getObjectField(activity, "g") as? View)
                                    recyclerView?.visibility = View.GONE

                                    // 显示空提示 TextView（若存在）
                                    val emptyTv = (XposedHelpers2.getObjectField(activity, "g") as? TextView)
                                        ?: (XposedHelpers2.getObjectField(activity, "m") as? TextView)
                                    emptyTv?.visibility = View.VISIBLE
                                    emptyTv?.text = "无聊天记录"
                                }
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                LogUtil.w("Hook $clazzName error", e)
            }
        }
    }

    /**
     * 3. 拦截多标签搜索页（包含图片、视频、文件搜索等）
     */
    private fun setEmptyActionBarTabPageUI(context: Context, lpparam: XC_LoadPackage.LoadPackageParam?) {
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
        return isUserLocked(username)
    }
}