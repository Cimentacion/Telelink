package app.telelink;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bloqueo de anuncios por dominio (coincide con el dominio y todos sus subdominios). */
final class AdBlock {

    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            // Google / grandes redes
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagservices.com", "googletagmanager.com", "google-analytics.com",
            "adservice.google.com", "pagead2.googlesyndication.com", "imasdk.googleapis.com",
            "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "criteo.com", "criteo.net",
            "taboola.com", "outbrain.com", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "smartadserver.com", "moatads.com", "scorecardresearch.com",
            "quantserve.com", "adform.net", "bidswitch.net", "yieldmo.com", "media.net",
            "revcontent.com", "mgid.com", "zedo.com", "adcolony.com", "applovin.com",
            "unityads.unity3d.com", "inmobi.com", "teads.tv", "sharethrough.com",
            "33across.com", "lijit.com", "sovrn.com", "contextweb.com", "advertising.com",
            "adsafeprotected.com", "doubleverify.com", "hotjar.com", "mixpanel.com",
            "facebook.net", "connect.facebook.net", "ads-twitter.com", "analytics.tiktok.com",
            // Redes de pop-ups / pop-unders típicas de webs de streaming
            "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
            "propeller-tracking.com", "adsterra.com", "adsterratech.com", "hilltopads.net",
            "hilltopads.com", "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com",
            "trafficjunky.net", "trafficjunky.com", "trafficstars.com", "tsyndicate.com",
            "clickadu.com", "clickaine.com", "adcash.com", "admaven.com", "ad-maven.com",
            "richpush.co", "richads.com", "monetag.com", "onclickads.net", "onclckds.com",
            "onclkds.com", "popmyads.com", "popunder.net", "poptm.com", "adspyglass.com",
            "bidvertiser.com", "mgid.io", "a-ads.com", "coinzilla.com", "cointraffic.io",
            "galaksion.com", "evadav.com", "pushground.com", "push.house", "notix.io",
            "onesignal.com", "pushwoosh.com", "aclickads.com", "adskeeper.co.uk",
            "adskeeper.com", "runative-syndicate.com", "runative.com", "plugrush.com",
            "ero-advertising.com", "adxxx.com", "twinrdsrv.com", "twinrdengine.com",
            "mc.yandex.ru", "an.yandex.ru", "betteradsystem.com", "displayvertising.com",
            "highperformanceformat.com", "highrevenuegate.com", "profitablegatecpm.com",
            "effectivegatecpm.com", "effectiveratecpm.com", "toprevenuegate.com",
            "alternativecpmgate.com", "weirdopt.com", "wpadmngr.com", "acscdn.com",
            "acdcdn.com", "aclib.net", "dtscout.com", "dtscdn.com", "smrtb.com",
            "rtmark.net", "tapioni.com", "vidoomy.com", "vdo.ai", "aniview.com",
            "spotxchange.com", "spotx.tv", "springserve.com", "innovid.com",
            "fwmrm.net", "adtng.com", "adnium.com", "ad.atdmt.com", "serving-sys.com",
            "bet365affiliates.com", "1xbet.com", "stake.com", "mostbet.com"
    ));

    private AdBlock() {}

    static boolean isAd(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        while (true) {
            if (HOSTS.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }
}
