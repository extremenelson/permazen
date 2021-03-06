
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package io.permazen.spring;

import com.google.common.base.Throwables;

import io.permazen.JTransaction;
import io.permazen.Permazen;
import io.permazen.ValidationMode;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A servlet {@link javax.servlet.Filter} binds a {@link Permazen} {@link JTransaction} to the current thread
 * for the entire processing of the request. Intended for the "Open Session in View" pattern, i.e. to allow
 * for lazy loading in web views despite the original transactions already being completed.
 *
 * <p>
 * This filter makes {@link JTransaction}s available via the current thread, which will be autodetected by
 * a {@link PermazenTransactionManager}.
 *
 * <p>
 * Looks up the {@link Permazen} in Spring's root web application context. Supports a {@code "PermazenBeanName"}
 * filter init-param in web.xml; the default bean name is {@code "permazen"}. Also supports setting the following
 * filter init-params:
 * <ul>
 *  <li>{@code "transactionAttributes"} - configures {@link TransactionAttribute}s; value should be
 *      a string compatible with {@link org.springframework.transaction.interceptor.TransactionAttributeEditor}.</li>
 *  <li>{@code "allowNewSchema"} - whether creation of a new schema version in the database is allowed.
 *      (see {@link Permazen#createTransaction Permazen.createTransaction()}). Default false.</li>
 *  <li>{@code "validationMode"} - validation mode for the transaction
 *      (see {@link Permazen#createTransaction Permazen.createTransaction()}). Default {@link ValidationMode#AUTOMATIC}.</li>
 * </ul>
 */
public class OpenTransactionInViewFilter extends OncePerRequestFilter {

    /**
     * The default name of the {@link Permazen} bean: <code>{@value}</code>.
     *
     * @see #PERMAZEN_BEAN_NAME_PARAMETER
     */
    public static final String DEFAULT_PERMAZEN_BEAN_NAME = "permazen";

    /**
     * Filter init parameter that specifies the name of the {@link Permazen} bean: <code>{@value}</code>.
     *
     * @see #DEFAULT_PERMAZEN_BEAN_NAME
     */
    public static final String PERMAZEN_BEAN_NAME_PARAMETER = "PermazenBeanName";

    /**
     * Filter init parameter that specifies transaction attributes: <code>{@value}</code>.
     */
    public static final String PERMAZEN_TRANSACTION_ATTRIBUTE_PARAMETER = "transactionAttributes";

    /**
     * Filter init parameter that specifies whether creation of a new schema version in the database is allowed.
     * Default false.
     */
    public static final String PERMAZEN_ALLOW_NEW_SCHEMA_PARAMETER = "allowNewSchema";

    /**
     * Filter init parameter that specifies the validation mode for the transaction.
     * Default {@link ValidationMode#AUTOMATIC}.
     */
    public static final String PERMAZEN_VALIDATION_MODE_PARAMETER = "validationMode";

    private String permazenBeanName = DEFAULT_PERMAZEN_BEAN_NAME;
    private TransactionAttribute transactionAttributes;
    private boolean allowNewSchema;
    private ValidationMode validationMode = ValidationMode.AUTOMATIC;

    private volatile Permazen jdb;

    /**
     * Get the name of the {@link Permazen} bean to find in the Spring root application context.
     *
     * @return bean name
     * @see #PERMAZEN_BEAN_NAME_PARAMETER
     */
    public String getPermazenBeanName() {
        return this.permazenBeanName;
    }

    /**
     * Set the name of the {@link Permazen} bean to find in the Spring root application context.
     * Default is {@link #DEFAULT_PERMAZEN_BEAN_NAME}.
     *
     * @param permazenBeanName {@link Permazen} bean name
     * @see #PERMAZEN_BEAN_NAME_PARAMETER
     */
    public void setPermazenBeanName(String permazenBeanName) {
        this.permazenBeanName = permazenBeanName;
    }

    /**
     * Get the transaction attributes.
     *
     * @return transaction attributes
     * @see #PERMAZEN_TRANSACTION_ATTRIBUTE_PARAMETER
     */
    public TransactionAttribute getTransactionAttributes() {
        return this.transactionAttributes;
    }

    /**
     * Set the transaction attributes.
     *
     * @param transactionAttributes transaction attributes
     * @see #PERMAZEN_TRANSACTION_ATTRIBUTE_PARAMETER
     */
    public void setTransactionAttributes(TransactionAttribute transactionAttributes) {
        this.transactionAttributes = transactionAttributes;
    }

    /**
     * Get whether new scheme creation is allowed.
     *
     * @return whether to allow recording new schema versions
     * @see #PERMAZEN_ALLOW_NEW_SCHEMA_PARAMETER
     */
    public boolean isAllowNewSchema() {
        return this.allowNewSchema;
    }

    /**
     * Set whether new scheme creation is allowed.
     *
     * @param allowNewSchema whether to allow recording new schema versions
     * @see #PERMAZEN_ALLOW_NEW_SCHEMA_PARAMETER
     */
    public void setAllowNewSchema(boolean allowNewSchema) {
        this.allowNewSchema = allowNewSchema;
    }

    /**
     * Get transaction validation mode.
     *
     * @return validation mode for transactions
     * @see #PERMAZEN_VALIDATION_MODE_PARAMETER
     */
    public ValidationMode getValidationMode() {
        return this.validationMode;
    }

    /**
     * Set transaction validation mode.
     *
     * @param validationMode validation mode for transactions
     * @see #PERMAZEN_VALIDATION_MODE_PARAMETER
     */
    public void setValidationMode(ValidationMode validationMode) {
        if (validationMode == null)
            validationMode = ValidationMode.AUTOMATIC;
        this.validationMode = validationMode;
    }

    /**
     * Look up the {@link Permazen} that this filter should use.
     *
     * @return the Permazen to use
     * @see #getPermazenBeanName
     */
    protected Permazen lookupPermazen() {
        if (this.jdb == null) {
            final WebApplicationContext wac = WebApplicationContextUtils.getRequiredWebApplicationContext(this.getServletContext());
            this.jdb = wac.getBean(this.getPermazenBeanName(), Permazen.class);
        }
        return this.jdb;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
      final FilterChain filterChain) throws ServletException, IOException {

        // Sanity check
        try {
            JTransaction.getCurrent();
            throw new IllegalStateException("a JTransaction is already associated with the current thread");
        } catch (IllegalStateException e) {
            // expected
        }

        // Get transaction attributes
        TransactionAttribute attr = this.transactionAttributes;
        if (attr == null)
            attr = new DefaultTransactionAttribute();

        // Create transaction
        final JTransaction jtx = this.lookupPermazen().createTransaction(this.allowNewSchema, this.validationMode);

        // Configure it
        if (attr.isReadOnly())
            jtx.getTransaction().setReadOnly(true);
        final int timeout = attr.getTimeout();
        if (timeout != TransactionAttribute.TIMEOUT_DEFAULT) {
            try {
                jtx.getTransaction().setTimeout(timeout * 1000L);
            } catch (UnsupportedOperationException e) {
                // ignore
            }
        }

        // Proceed with transaction
        boolean success = false;
        try {
            JTransaction.setCurrent(jtx);
            filterChain.doFilter(request, response);
            success = true;
        } catch (Throwable t) {
            if (attr.rollbackOn(t) || jtx.getTransaction().isRollbackOnly())
                jtx.rollback();
            else
                jtx.commit();
            Throwables.propagateIfPossible(t, ServletException.class, IOException.class);
            throw new RuntimeException(t);
        } finally {
            JTransaction.setCurrent(null);
            if (success) {
                if (jtx.getTransaction().isRollbackOnly())
                    jtx.rollback();
                else
                    jtx.commit();
            }
        }
    }
}

