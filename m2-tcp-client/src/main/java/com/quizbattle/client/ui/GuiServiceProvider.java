package com.quizbattle.client.ui;

import com.quizbattle.client.service.GameClientService;
import com.quizbattle.common.AppConfig;

/** App integration may register a real provider with ServiceLoader. No mock fallback. */
public interface GuiServiceProvider {
    GameClientService create(AppConfig config) throws Exception;
}
