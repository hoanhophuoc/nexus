package com.haichutieu.chatsystem.server.dal;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.util.Properties;

import static com.haichutieu.chatsystem.server.SocketServer.properties;

public class HibernateUtil {
    private final SessionFactory sessionFactory;

    private HibernateUtil() {
        try {
            Configuration configuration = new Configuration();

            // Only pass Hibernate its own settings; the configuration file also holds
            // server and mail settings.
            Properties hibernateProperties = new Properties();
            properties.stringPropertyNames().stream()
                    .filter(name -> name.startsWith("hibernate."))
                    .forEach(name -> hibernateProperties.setProperty(name, properties.getProperty(name)));
            configuration.addProperties(hibernateProperties);

            // Load mappings from hibernate.cfg.xml
            configuration.configure("hibernate.cfg.xml");

            // Build session factory
            sessionFactory = configuration.buildSessionFactory();
        } catch (Throwable ex) {
            System.err.println("Initial SessionFactory creation failed: " + ex.getMessage());
            throw new ExceptionInInitializerError(ex);
        }
    }

    public static HibernateUtil getInstance() {
        return HibernateUtil.SessionManagerHelper.INSTANCE;
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    private static class SessionManagerHelper {
        private static final HibernateUtil INSTANCE = new HibernateUtil();
    }
}
