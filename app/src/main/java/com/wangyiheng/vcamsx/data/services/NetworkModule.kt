package com.wangyiheng.vcamsx.data.services

import cn.dianbobo.dbb.util.HLog
import com.wangyiheng.vcamsx.MainHook.Companion.TAG
import okhttp3.OkHttpClient
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val BASE_URL = "https://vcamsx.gptmanage.top/"

val networkModule = module {

//    factory { ApiInterceptor(androidContext(),get<InfoManager>()) }

    single {
        OkHttpClient.Builder()
//            .addInterceptor(get<ApiInterceptor>())
            .build()
    }

    single {
        HLog.d(TAG,"aaa 1111111111111111111 networkModule,single) ）")
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(get<OkHttpClient>())
            .build()
    }

    single { get<Retrofit>().create(ApiService::class.java) }
}