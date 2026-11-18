package com.marketradar.mobile;

/** Human-readable final status for a marketplace with no verified offers. */
final class StoreResultMessage {
    private StoreResultMessage(){}

    static String summarize(String raw){
        String state=raw==null?"":raw.toLowerCase(java.util.Locale.ROOT);
        if(state.contains("повторно войти")||state.contains("авторизац")||
           state.contains("повторный вход"))return "нужен вход";
        if(state.contains("http 403"))return "ответ сервера HTTP 403";
        if(state.contains("http 429"))return "ответ сервера HTTP 429";
        if(state.contains("страницу проверки доступа")||
           state.contains("проверка браузера не завершилась")||
           state.contains("captcha"))return "требуется проверка сайта";
        if(state.contains("ограничил"))return "сайт не предоставил выдачу";
        if(state.contains("переадрес")||state.contains("перенаправ"))
            return "сайт перенаправил поиск";
        if(state.contains("не ответила")||state.contains("нет прогресса")||
           state.contains("ошибка загрузки")||state.contains("не удалось загрузить"))
            return "выдача не загрузилась";
        if(state.contains("карточек")||state.contains("нет подходящих")||
           state.contains("неполна")||state.contains("проверено"))
            return "товары не подтверждены";
        return "предложения не получены";
    }
}
