package com.rummiclub.app;

import java.net.*;
import javax.net.ssl.SSLException;

/** User-facing transport diagnostics; never includes headers, tokens or request bodies. */
final class ConnectionErrors {
    static String describe(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException) return "Телефон не смог найти адрес сервера. Проверьте Private DNS, VPN или попробуйте другую сеть. Код: DNS";
            if (cause instanceof SSLException) return "Не удалось установить защищённое соединение. Проверьте дату и время телефона, VPN и фильтрацию HTTPS. Код: TLS";
            if (cause instanceof SocketTimeoutException) return "Сервер не ответил вовремя. Попробуйте другую сеть или подключитесь ещё раз. Код: TIMEOUT";
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) return "Не удалось соединиться с сервером. Проверьте доступ к нему через Wi-Fi и мобильную сеть. Код: CONNECT";
            if (cause instanceof InterruptedException) return "Подключение отменено";
        }
        if (error instanceof ServerWarmup.NotReady) return "Сервер не подтвердил готовность за две минуты. Откройте проверку сервера и повторите подключение.";
        return "Не удалось получить ответ сервера. Код: " + error.getClass().getSimpleName();
    }
}
