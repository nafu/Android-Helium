package com.github.gfx.helium.model;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;

import com.cookpad.android.rxt4a.subscriptions.AndroidCompositeSubscription;
import com.github.gfx.helium.BuildConfig;
import com.github.gfx.helium.api.EpitomeClient;
import com.github.gfx.helium.api.HatenaClient;
import com.github.gfx.helium.api.HeliumRequestInterceptor;
import com.github.gfx.helium.util.AppTracker;
import com.github.gfx.helium.util.LoadingAnimation;
import com.github.gfx.helium.util.ViewSwitcher;
import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import retrofit.RequestInterceptor;
import retrofit.client.Client;
import retrofit.client.OkClient;

@Module
public class AppModule {

    static final String CACHE_FILE_NAME = "okhttp.cache";

    static final long MAX_CACHE_SIZE = 4 * 1024 * 1024;

    static final String SHARED_PREF_NAME = "preferences";

    private Context context;

    public AppModule(Application app) {
        context = app;
    }

    @Provides
    public Context provideContext() {
        return context;
    }

    @Singleton
    @Provides
    public Tracker providesGoogleAnalyticsTracker(Context context) {
        GoogleAnalytics ga = GoogleAnalytics.getInstance(context);
        Tracker tracker = ga.newTracker(BuildConfig.GA_TRACKING_ID);
        tracker.enableExceptionReporting(true);
        return tracker;
    }

    @Singleton
    @Provides
    public AppTracker getAppTracker(Tracker tracker) {
        return new AppTracker(tracker);
    }

    @Provides
    public RequestInterceptor provideRequestInterceptor(Context context) {
        return new HeliumRequestInterceptor(context);
    }

    @Singleton
    @Provides
    public OkHttpClient provideHttpClient(Context context) {
        File cacheDir = new File(context.getCacheDir(), CACHE_FILE_NAME);
        Cache cache = new Cache(cacheDir, MAX_CACHE_SIZE);

        OkHttpClient httpClient = new OkHttpClient();
        httpClient.setCache(cache);
        return httpClient;
    }

    @Provides
    public Client provideRetrofitClient(OkHttpClient httpClient) {
        return new OkClient(httpClient);
    }

    @Singleton
    @Provides
    public HatenaClient provideHatebuFeedClient(Client client,
            RequestInterceptor requestInterceptor) {
        return new HatenaClient(client, requestInterceptor);
    }

    @Singleton
    @Provides
    public EpitomeClient provideEpitomeFeedClient(Client client,
            RequestInterceptor requestInterceptor) {
        return new EpitomeClient(client, requestInterceptor);
    }

    @Provides
    public SharedPreferences provideSharedPreferences(Context context) {
        return context.getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE);
    }

    @Provides
    public AndroidCompositeSubscription provideAndroidCompositeSubscription() {
        return new AndroidCompositeSubscription();
    }

    @Provides
    public ViewSwitcher provideViewSwitcher(Context context) {
        return new ViewSwitcher(context);
    }

    @Provides
    public LoadingAnimation provideLoadingAnimations() {
        return new LoadingAnimation();
    }
}
