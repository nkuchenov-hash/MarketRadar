package com.marketradar.mobile;

/** Human-readable final status for a marketplace with no verified offers. */
final class StoreResultMessage {
    private StoreResultMessage(){}

    static String summarize(String raw){
        String state=raw==null?"":raw.toLowerCase(java.util.Locale.ROOT);
        if(state.contains("повторно войти")||state.contains("авторизац")||
           state.contains("повторный вход"))return "нужен вход";
        if(state.contains("ограничил")||state.contains("http 403")||
           state.contains("http 429")||state.contains("captcha")||
           state.contains("проверка браузера не завершилась"))
            return "сайт ограничил доступ";
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
