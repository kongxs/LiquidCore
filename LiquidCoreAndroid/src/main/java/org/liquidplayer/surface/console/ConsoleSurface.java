/*
 * Copyright (c) 2016 - 2018 Eric Lange
 *
 * Distributed under the MIT License.  See LICENSE.md at
 * https://github.com/LiquidPlayer/LiquidCore for terms and conditions.
 */
package org.liquidplayer.surface.console;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.View;

import org.liquidplayer.javascript.JSContext;
import org.liquidplayer.javascript.JSException;
import org.liquidplayer.javascript.JSFunction;
import org.liquidplayer.javascript.JSObject;
import org.liquidplayer.javascript.JSValue;
import org.liquidplayer.node.BuildConfig;
import org.liquidplayer.node.Process;
import org.liquidplayer.service.MicroService;
import org.liquidplayer.service.Surface;

import java.util.HashMap;

/**
 * A ConsoleSurface is a node.js ANSI text console.  ConsoleSurface operates by manipulating
 * the 'process' object in node.  It captures output written to stdout and stderr as well as
 * traps and displays any JavaScript exceptions.
 *
 * So long as the underlying MicroService is still running, ConsoleSurface can inject javascript
 * into a running process through a command line.
 *
 * ConsoleSurface is intended to be used mostly for debugging.
 */
public class ConsoleSurface extends ConsoleView implements Surface {

    public static String SURFACE_VERSION = BuildConfig.VERSION_NAME;

    public ConsoleSurface(Context context) {
        this(context, null);
    }

    public ConsoleSurface(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ConsoleSurface(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setSaveEnabled(true);
    }

    private ConsoleSession session = null;

    @Override
    public void reset() {
        detach();
        super.reset();
    }

    @Override
    protected void resize(int columns, int rows) {
        if (session != null) {
            session.resize(columns,rows);
        }
    }

    @Override
    public void bind(MicroService service, JSContext context, JSObject export, JSValue config,
                     Runnable onBound, ReportErrorRunnable onError) {
        // Nothing to do -- everything happens during attach
        onBound.run();
    }

    /**
     * ConsoleSurface is somewhat unusal for Surfaces.  It can be attached and detached at any
     * time during the MicroService lifecycle, as it dynamically overrides stderr, stdout and
     * handles exceptions.  There is no special interface required on the JavaScript side since
     * it operates purely on standard streams.  Therefore, no setup is required before
     * running the service.
     * @param service  The MicroService to attach
     */
    @Override
    public View attach(MicroService service, Runnable onAttached, ReportErrorRunnable onError) {
        uuid = service.getId();
        session = ConsoleSession.newSession(service, this, onAttached);
        return this;
    }

    @Override
    public void detach() {
        if (session != null) {
            session.detach();
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                inputBox.setEnabled(false);
                inputBox.setClickable(false);
                inputBox.setAlpha(0.5f);
                setButtonEnabled(downHistory,false);
                setButtonEnabled(upHistory,false);
            }
        });
    }

    @Override
    protected void processCommand(final String cmd) {
        if (session != null) {
            session.processCommand(cmd);
        }
    }

    private String temp = "";
    void print(String str) {
        if (consoleTextView == null) {
            temp = temp + "\u001b[m" + str;
        } else {
            if (temp.length() > 0) {
                consoleTextView.print(temp);
                temp = "";
            }
            consoleTextView.print(str);
        }
    }
    void println(String str) {
        print(str + "\n");
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (temp.length() > 0) {
            consoleTextView.print(temp);
            temp = "";
        }
    }

    /* -- parcelable privates -- */
    private String uuid;

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.uuid = uuid;
        if (session != null) {
            session.removeCurrentView(this);
        }
        session = null;
        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        uuid = ss.uuid;
        session = ConsoleSession.getSessionFromServiceId(uuid);
    }

    static class SavedState extends BaseSavedState {
        String uuid;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            uuid = in.readString();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeString(uuid);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private static class ConsoleSession implements Process.EventListener,
            JSContext.IJSExceptionHandler {

        static ConsoleSession newSession(MicroService service, ConsoleSurface view, Runnable onAttached) {
            ConsoleSession session = new ConsoleSession(service,view,onAttached);
            sessionMap.put(service.getId(), session);
            return session;
        }

        static ConsoleSession getSessionFromServiceId(String id) {
            return sessionMap.get(id);
        }

        private static HashMap<String,ConsoleSession> sessionMap = new HashMap<>();

        private ConsoleSession(MicroService service, ConsoleSurface view, Runnable onAttached) {
            this.onAttached = onAttached;
            this.currentView = view;
            if (service != null) {
                process = service.getProcess();
                process.addEventListener(this);
                uuid = service.getId();
            }
        }

        void detach() {
            if (js != null && process.isActive()) {
                JSObject stdout =
                        js.property("process").toObject().property("stdout").toObject();
                JSObject stderr =
                        js.property("process").toObject().property("stderr").toObject();
                teardownStream(stdout);
                teardownStream(stderr);
            }

            console_log = null;
            js = null;
            process.removeEventListener(this);
            if (uuid != null) {
                sessionMap.remove(uuid);
            }
        }

        void setCurrentView(ConsoleSurface view) {
            currentView = view;
        }

        void removeCurrentView(ConsoleSurface view) {
            if (currentView == view) currentView = null;
        }

        void processCommand(final String cmd) {
            processedException = false;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    JSValue output = js.evaluateScript(cmd);
                    if (!processedException && console_log != null) {
                        console_log.call(null, output);
                    }
                }
            }).start();
        }

        void resize(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }

        private interface StreamCallback {
            void callback(String string);
        }
        private void setupStream(final JSObject stream, final StreamCallback callback) {
            stream.property("write", new JSFunction(stream.getContext(), "write") {
                @SuppressWarnings("unused")
                public void write(final String string) {
                    callback.callback(string);
                }
            });
            stream.property("clearScreenDown", new JSFunction(stream.getContext(),"clearScreenDown",
                    "this.write('\\x1b[0J');"));
            stream.property("moveCursor", new JSFunction(stream.getContext(),"moveCursor",
                    "var out = ''; c = c || 0; r = r || 0;" +
                            "if (c>0) out += '\\x1b['+c+'C'; else if (c<0) out+='\\x1b['+(-c)+'D';"+
                            "if (r>0) out += '\\x1b['+r+'B'; else if (r<0) out+='\\x1b['+(-r)+'A';"+
                            "this.write(out);",
                    "c", "r"));
            stream.property("rows", rows);
            stream.property("columns", columns);
        }
        private void teardownStream(final JSObject stream) {
            // FIXME: restore previous function
            stream.property("write", new JSFunction(js));
            stream.deleteProperty("clearScreenDown");
            stream.deleteProperty("moveCursor");
        }

        @Override
        public void onProcessStart(Process process, JSContext context) {
            js = context;
            js.setExceptionHandler(this);
            console_log = js.property("console").toObject().property("log").toFunction();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    if (currentView != null) {
                        currentView.inputBox.setEnabled(true);
                    }
                }
            });

            JSObject stdout =
                    js.property("process").toObject().property("stdout").toObject();
            JSObject stderr =
                    js.property("process").toObject().property("stderr").toObject();
            setupStream(stdout, new StreamCallback() {
                @Override
                public void callback(String string) {
                    if (currentView != null) {
                        currentView.print(string);
                    }
                }
            });
            setupStream(stderr, new StreamCallback() {
                @Override
                public void callback(String string) {
                    // Make it red!
                    string = "\u001b[31m" + string;
                    if (currentView != null) {
                        currentView.print(string);
                    }
                    android.util.Log.e("stderr", string);
                }
            });
            if (onAttached != null) {
                onAttached.run();
                onAttached = null;
            }
        }

        @Override
        public void onProcessAboutToExit(Process process, int exitCode) {
            if (currentView != null) {
                currentView.println(
                        "\u001B[31mProcess about to exit with code " + exitCode);
            }
            android.util.Log.i("onProcessAboutToExit", "Process about to exit with code "+exitCode);
            detach();
        }

        @Override
        public void onProcessExit(Process process, int exitCode) {
            android.util.Log.i("onProcessExit", "exiting");
            if (currentView != null) {
                currentView.println("\u001B[31mProcess exited with code "+exitCode);
            }
            this.process = null;
        }

        @Override
        public void onProcessFailed(Process process, Exception error) {

        }

        @Override
        public void handle(final JSException e) {
            processedException = true;
            if (currentView != null) {
                currentView.println("\u001b[31m" + e.stack());
            }
        }

        private Process process = null;
        private JSContext js = null;
        private JSFunction console_log = null;
        private Runnable onAttached;
        private ConsoleSurface currentView;
        private boolean processedException = false;
        private int columns = 0, rows = 0;
        private String uuid = null;
    }

}
