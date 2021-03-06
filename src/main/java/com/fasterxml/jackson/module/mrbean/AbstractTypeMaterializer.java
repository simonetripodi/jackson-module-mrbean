package com.fasterxml.jackson.module.mrbean;

import java.lang.reflect.Modifier;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.Versioned;
import com.fasterxml.jackson.databind.AbstractTypeResolver;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;

/**
 * Nifty class for pulling implementations of classes out of thin air.
 *<p>
 * ... friends call him Mister Bean... :-)
 * 
 * @author tatu
 * @author sunny
 */
public class AbstractTypeMaterializer
    extends AbstractTypeResolver
    implements Versioned
{
    /**
     * Enumeration that defines togglable features that guide
     * the serialization feature.
     */
    public enum Feature {
        /**
         * Feature that determines what happens if an "unrecognized"
         * (non-getter, non-setter) abstract method is encountered: if set to
         * true, will throw an exception during materialization; if false,
         * will materialize method that throws exception only if called.
         */
        FAIL_ON_UNMATERIALIZED_METHOD(false),
        
        /**
         * Feature that determines what happens when attempt is made to
         * generate implementation of non-public class or interface.
         * If true, an exception is thrown; if false, will just quietly
         * ignore attempts.
         */
        FAIL_ON_NON_PUBLIC_TYPES(true)
        ;

        final boolean _defaultState;

        // Method that calculates bit set (flags) of all features that are enabled by default.
        protected static int collectDefaults() {
            int flags = 0;
            for (Feature f : values()) {
                if (f.enabledByDefault()) {
                    flags |= f.getMask();
                }
            }
            return flags;
        }
                
        private Feature(boolean defaultState) { _defaultState = defaultState; }
        public boolean enabledByDefault() { return _defaultState; }
        public int getMask() { return (1 << ordinal()); }
    }

    /**
     * Bitfield (set of flags) of all Features that are enabled
     * by default.
     */
    protected final static int DEFAULT_FEATURE_FLAGS = Feature.collectDefaults();

    /**
     * Default package to use for generated classes.
     */
    public final static String DEFAULT_PACKAGE_FOR_GENERATED = "org.codehaus.jackson.generated.";
    
    /**
     * We will use per-materializer class loader for now; would be nice
     * to find a way to reduce number of class loaders (and hence
     * number of generated classes!) constructed...
     */
    protected final MyClassLoader _classLoader;

    /**
     * Bit set that contains all enabled features
     */
    protected int _featureFlags = DEFAULT_FEATURE_FLAGS;

    /**
     * Package name to use as prefix for generated classes.
     */
    protected String _defaultPackage = DEFAULT_PACKAGE_FOR_GENERATED;
    
    /*
    /**********************************************************
    /* Construction, configuration
    /**********************************************************
     */
    
    public AbstractTypeMaterializer() {
        this(null);
    }

    
    /**
     * @param parentClassLoader Class loader to use for generated classes; if
     *   null, will use class loader that loaded materializer itself.
     */
    public AbstractTypeMaterializer(ClassLoader parentClassLoader)
    {
        if (parentClassLoader == null) {
            parentClassLoader = getClass().getClassLoader();
        }
        _classLoader = new MyClassLoader(parentClassLoader);
    }

    /**
     * Method that will return version information stored in and read from jar
     * that contains this class.
     */
    //@Override
    public Version version() {
        return ModuleVersion.instance.version();
    }
    
    /**
     * Method for checking whether given feature is enabled or not
     */
    public final boolean isEnabled(Feature f) {
        return (_featureFlags & f.getMask()) != 0;
    }

    /**
     * Method for enabling specified  feature.
     */
    public void enable(Feature f) {
        _featureFlags |= f.getMask();
    }

    /**
     * Method for disabling specified feature.
     */
    public void disable(Feature f) {
        _featureFlags &= ~f.getMask();
    }

    /**
     * Method for enabling or disabling specified feature.
     */
    public void set(Feature f, boolean state)
    {
        if (state) {
            enable(f);
        } else {
            disable(f);
        }
    }

    /**
     * Method for specifying package to use for generated classes.
     */
    public void setDefaultPackage(String defPkg)
    {
        if (!defPkg.endsWith(".")) {
            defPkg = defPkg + ".";
        }
        _defaultPackage = defPkg;
    }
    
    /*
    /**********************************************************
    /* Public API
    /**********************************************************
     */
    
    @Override
    public JavaType resolveAbstractType(DeserializationConfig config, JavaType type)
    {
        /* 19-Feb-2011, tatu: Future plans may include calling of this method for all kinds
         *    of abstract types. So as simple precaution, let's limit kinds of types we
         *    will try materializa implementations for.
         */
        /* We won't be handling any container types (Collections, Maps and arrays),
         * Throwables or enums.
         */
        if (type.isContainerType() || type.isPrimitive() || type.isEnumType() || type.isThrowable()) {
            return null;
        }
        Class<?> cls = type.getRawClass();
        /* [JACKSON-683] Fail on non-public classes, since we can't easily force
         *   access to such classes (unless we tried to generate impl classes in that
         *   package)
         */
        if (!Modifier.isPublic(cls.getModifiers())) {
            if (isEnabled(Feature.FAIL_ON_NON_PUBLIC_TYPES)) {
                throw new IllegalArgumentException("Can not materialize implementation of "+cls+" since it is not public ");
            }
            return null;
        }
        
        // might want to skip proxies, local types too... but let them be for now:
        //if (intr.findTypeResolver(beanDesc.getClassInfo(), type) == null) {
        return config.constructType(materializeClass(config, cls));
    }

    /**
     * Method that will find implementation for given abstract class; if called
     * multiple times on same materializer, will return same Class.
     * 
     * @param config Configuration settings to use; mostly needed to be able to
     *     access {@link com.fasterxml.jackson.databind.type.TypeFactory}
     */
    public Class<?> materializeClass(DeserializationConfig config, Class<?> cls)
    {
        // Need to have proper name mangling in future, but for now...
        String newName = _defaultPackage+cls.getName();
        BeanBuilder builder = new BeanBuilder(cls, config.getTypeFactory());
        byte[] bytecode = builder.implement(isEnabled(Feature.FAIL_ON_UNMATERIALIZED_METHOD)).build(newName);
        Class<?> result = _classLoader.loadAndResolve(newName, bytecode, cls);
        return result;
    }
    
    /*
    /**********************************************************
    /* Helper classes
    /**********************************************************
     */

    /**
     * To support actual dynamic loading of bytecode we need a simple
     * custom classloader.
     */
    private static class MyClassLoader extends ClassLoader
    {
        public MyClassLoader(ClassLoader parent)
        {
            super(parent);
        }

        /**
         * @param targetClass Interface or abstract class that class to load should extend or 
         *   implement
         */
        public Class<?> loadAndResolve(String className, byte[] byteCode, Class<?> targetClass)
            throws IllegalArgumentException
        {
            // First things first: just to be sure; maybe we have already loaded it?
            Class<?> old = findLoadedClass(className);
            if (old != null && targetClass.isAssignableFrom(old)) {
                return old;
            }
            
            Class<?> impl;
            try {
                impl = defineClass(className, byteCode, 0, byteCode.length);
            } catch (LinkageError e) {
                throw new IllegalArgumentException("Failed to load class '"+className+"': "+e.getMessage() ,e);
            }
            // important: must also resolve the class...
            resolveClass(impl);
            return impl;
        }
    }
}
