/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.voice.internal;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.UnsupportedAudioFormatException;
import org.openhab.core.audio.UnsupportedAudioStreamException;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.i18n.TranslationProvider;
import org.openhab.core.items.ItemUtil;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.voice.KSErrorEvent;
import org.openhab.core.voice.KSEvent;
import org.openhab.core.voice.KSException;
import org.openhab.core.voice.KSListener;
import org.openhab.core.voice.KSService;
import org.openhab.core.voice.KSServiceHandle;
import org.openhab.core.voice.KSpottedEvent;
import org.openhab.core.voice.RecognitionStartEvent;
import org.openhab.core.voice.RecognitionStopEvent;
import org.openhab.core.voice.STTEvent;
import org.openhab.core.voice.STTException;
import org.openhab.core.voice.STTListener;
import org.openhab.core.voice.STTService;
import org.openhab.core.voice.STTServiceHandle;
import org.openhab.core.voice.SpeechRecognitionErrorEvent;
import org.openhab.core.voice.SpeechRecognitionEvent;
import org.openhab.core.voice.TTSException;
import org.openhab.core.voice.TTSService;
import org.openhab.core.voice.Voice;
import org.openhab.core.voice.text.HumanLanguageInterpreter;
import org.openhab.core.voice.text.InterpretationException;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An instance of this class can handle a complete dialog with the user. It orchestrates the keyword spotting, the stt
 * and tts services together with the human language interpreter.
 *
 * @author Kai Kreuzer - Initial contribution
 * @author Yannick Schaus - Send commands to an item to indicate the keyword has been spotted
 * @author Christoph Weitkamp - Added getSupportedStreams() and UnsupportedAudioStreamException
 * @author Christoph Weitkamp - Added parameter to adjust the volume
 * @author Laurent Garnier - Added stop() + null annotations + resources releasing
 * @author Miguel Álvarez - Close audio streams + use RecognitionStartEvent
 */
@NonNullByDefault
public class DialogProcessor implements KSListener, STTListener {

    private final Logger logger = LoggerFactory.getLogger(DialogProcessor.class);

    private final KSService ks;
    private final STTService stt;
    private final TTSService tts;
    private final HumanLanguageInterpreter hli;
    private final AudioSource source;
    private final AudioSink sink;
    private final Locale locale;
    private final String keyword;
    private final @Nullable String listeningItem;
    private final EventPublisher eventPublisher;
    private final TranslationProvider i18nProvider;
    private final Bundle bundle;

    private final @Nullable AudioFormat ksFormat;
    private final @Nullable AudioFormat sttFormat;
    private final @Nullable AudioFormat ttsFormat;

    /**
     * If the processor is currently processing a keyword event and thus should not spot further ones.
     */
    private boolean processing = false;

    /**
     * If the STT server is in the process of aborting
     */
    private boolean isSTTServerAborting = false;

    private @Nullable KSServiceHandle ksServiceHandle;
    private @Nullable STTServiceHandle sttServiceHandle;

    private @Nullable AudioStream streamKS;
    private @Nullable AudioStream streamSTT;

    public DialogProcessor(KSService ks, STTService stt, TTSService tts, HumanLanguageInterpreter hli,
            AudioSource source, AudioSink sink, Locale locale, String keyword, @Nullable String listeningItem,
            EventPublisher eventPublisher, TranslationProvider i18nProvider, Bundle bundle) {
        this.locale = locale;
        this.ks = ks;
        this.hli = hli;
        this.stt = stt;
        this.tts = tts;
        this.source = source;
        this.sink = sink;
        this.keyword = keyword;
        this.listeningItem = listeningItem;
        this.eventPublisher = eventPublisher;
        this.i18nProvider = i18nProvider;
        this.bundle = bundle;
        this.ksFormat = VoiceManagerImpl.getBestMatch(source.getSupportedFormats(), ks.getSupportedFormats());
        this.sttFormat = VoiceManagerImpl.getBestMatch(source.getSupportedFormats(), stt.getSupportedFormats());
        this.ttsFormat = VoiceManagerImpl.getBestMatch(tts.getSupportedFormats(), sink.getSupportedFormats());
    }

    public void start() {
        AudioFormat fmt = ksFormat;
        if (fmt == null) {
            logger.warn("No compatible audio format found for ks '{}' and source '{}'", ks.getId(), source.getId());
            return;
        }
        abortKS();
        closeStreamKS();
        try {
            AudioStream stream = source.getInputStream(fmt);
            streamKS = stream;
            ksServiceHandle = ks.spot(this, stream, locale, keyword);
        } catch (AudioException e) {
            logger.warn("Encountered audio error: {}", e.getMessage());
        } catch (KSException e) {
            logger.warn("Encountered error calling spot: {}", e.getMessage());
            closeStreamKS();
        }
    }

    public void stop() {
        abortSTT();
        closeStreamSTT();
        abortKS();
        closeStreamKS();
        toggleProcessing(false);
    }

    private void abortKS() {
        KSServiceHandle handle = ksServiceHandle;
        if (handle != null) {
            handle.abort();
            ksServiceHandle = null;
        }
    }

    private void closeStreamKS() {
        AudioStream stream = streamKS;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                logger.debug("IOException closing ks audio stream: {}", e.getMessage(), e);
            }
            streamKS = null;
        }
    }

    private void abortSTT() {
        STTServiceHandle handle = sttServiceHandle;
        if (handle != null) {
            handle.abort();
            sttServiceHandle = null;
        }
        isSTTServerAborting = true;
    }

    private void closeStreamSTT() {
        AudioStream stream = streamSTT;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                logger.debug("IOException closing stt audio stream: {}", e.getMessage(), e);
            }
            streamSTT = null;
        }
    }

    private void toggleProcessing(boolean value) {
        if (processing == value) {
            return;
        }
        processing = value;
        String item = listeningItem;
        if (item != null && ItemUtil.isValidItemName(item)) {
            OnOffType command = (value) ? OnOffType.ON : OnOffType.OFF;
            eventPublisher.post(ItemEventFactory.createCommandEvent(item, command));
        }
    }

    @Override
    public void ksEventReceived(KSEvent ksEvent) {
        if (!processing) {
            isSTTServerAborting = false;
            if (ksEvent instanceof KSpottedEvent) {
                abortSTT();
                closeStreamSTT();
                isSTTServerAborting = false;
                AudioFormat fmt = sttFormat;
                if (fmt != null) {
                    try {
                        AudioStream stream = source.getInputStream(fmt);
                        streamSTT = stream;
                        sttServiceHandle = stt.recognize(this, stream, locale, new HashSet<>());
                    } catch (AudioException e) {
                        logger.warn("Error creating the audio stream: {}", e.getMessage());
                    } catch (STTException e) {
                        closeStreamSTT();
                        String msg = e.getMessage();
                        String text = i18nProvider.getText(bundle, "error.stt-exception", null, locale);
                        if (msg != null) {
                            say(text == null ? msg : text.replace("{0}", msg));
                        } else if (text != null) {
                            say(text.replace("{0}", ""));
                        }
                    }
                } else {
                    logger.warn("No compatible audio format found for stt '{}' and source '{}'", stt.getId(),
                            source.getId());
                }
            } else if (ksEvent instanceof KSErrorEvent) {
                KSErrorEvent kse = (KSErrorEvent) ksEvent;
                String text = i18nProvider.getText(bundle, "error.ks-error", null, locale);
                say(text == null ? kse.getMessage() : text.replace("{0}", kse.getMessage()));
            }
        }
    }

    @Override
    public synchronized void sttEventReceived(STTEvent sttEvent) {
        if (sttEvent instanceof SpeechRecognitionEvent) {
            if (!isSTTServerAborting) {
                SpeechRecognitionEvent sre = (SpeechRecognitionEvent) sttEvent;
                String question = sre.getTranscript();
                try {
                    toggleProcessing(false);
                    say(hli.interpret(locale, question));
                } catch (InterpretationException e) {
                    String msg = e.getMessage();
                    if (msg != null) {
                        say(msg);
                    }
                }
                abortSTT();
            }
        } else if (sttEvent instanceof RecognitionStartEvent) {
            toggleProcessing(true);
        } else if (sttEvent instanceof RecognitionStopEvent) {
            toggleProcessing(false);
        } else if (sttEvent instanceof SpeechRecognitionErrorEvent) {
            if (!isSTTServerAborting) {
                abortSTT();
                toggleProcessing(false);
                SpeechRecognitionErrorEvent sre = (SpeechRecognitionErrorEvent) sttEvent;
                String text = i18nProvider.getText(bundle, "error.stt-error", null, locale);
                say(text == null ? sre.getMessage() : text.replace("{0}", sre.getMessage()));
            }
        }
    }

    /**
     * Says the passed command
     *
     * @param text The text to say
     */
    protected void say(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            logger.debug("Empty value, nothing to say");
            return;
        }
        try {
            Voice voice = null;
            for (Voice currentVoice : tts.getAvailableVoices()) {
                if (locale.getLanguage().equals(currentVoice.getLocale().getLanguage())) {
                    voice = currentVoice;
                    break;
                }
            }
            if (voice == null) {
                throw new TTSException("Unable to find a suitable voice");
            }

            AudioFormat audioFormat = ttsFormat;
            if (audioFormat == null) {
                throw new TTSException("No compatible audio format found for TTS '" + tts.getId() + "' and sink '"
                        + sink.getId() + "'");
            }

            AudioStream audioStream = tts.synthesize(text, voice, audioFormat);

            if (sink.getSupportedStreams().stream().anyMatch(clazz -> clazz.isInstance(audioStream))) {
                try {
                    sink.process(audioStream);
                } catch (UnsupportedAudioFormatException | UnsupportedAudioStreamException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Error saying '{}': {}", text, e.getMessage(), e);
                    } else {
                        logger.warn("Error saying '{}': {}", text, e.getMessage());
                    }
                }
            } else {
                logger.warn("Failed playing audio stream '{}' as audio doesn't support it.", audioStream);
            }
        } catch (TTSException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Error saying '{}': {}", text, e.getMessage(), e);
            } else {
                logger.warn("Error saying '{}': {}", text, e.getMessage());
            }
        }
    }
}
