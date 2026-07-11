package com.pavbhaji.pos

import android.annotation.SuppressLint
import android.content.Context
import com.pavbhaji.pos.data.Api
import com.pavbhaji.pos.data.Live
import com.pavbhaji.pos.data.Session

@SuppressLint("StaticFieldLeak")
object Deps {
    lateinit var session: Session
    lateinit var api: Api
    lateinit var live: Live

    fun init(context: Context) {
        if (::session.isInitialized) return
        session = Session(context.applicationContext)
        api = Api(session)
        live = Live(session, api)
    }
}
