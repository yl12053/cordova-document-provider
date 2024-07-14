package com.nemo.documentProvider;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.json.JSONArray;

import java.io.File;

public class DocumentProviderPlugin extends CordovaPlugin{
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext){
        if ("getConfig".equals(action)){
            File config = new File(CordovaInterface.getContext().getFilesDir(), "DocumentProvider.json");
            if (!config.exists()){
                callbackContext.sendPluginResult()
            }
        }
    }
}
