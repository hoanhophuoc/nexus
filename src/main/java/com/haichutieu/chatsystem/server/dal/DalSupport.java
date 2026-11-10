package com.haichutieu.chatsystem.server.dal;

import org.hibernate.Transaction;

final class DalSupport {

    private DalSupport() {
    }

    /**
     * A constraint violation can close Hibernate's logical connection before the
     * catch block runs; a failed rollback must not hide the original error.
     */
    static void rollbackQuietly(Transaction transaction) {
        try {
            if (transaction != null && transaction.isActive()) {
                transaction.rollback();
            }
        } catch (RuntimeException ignored) {
            // the connection is already closed
        }
    }
}
