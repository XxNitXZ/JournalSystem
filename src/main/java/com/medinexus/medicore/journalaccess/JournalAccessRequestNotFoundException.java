package com.medinexus.medicore.journalaccess;

/** No journal access request with this ID. */
public class JournalAccessRequestNotFoundException extends RuntimeException {

    public JournalAccessRequestNotFoundException() {
        super("Journal access request not found");
    }
}
