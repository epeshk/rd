﻿using System;
using System.Collections.Generic;
using JetBrains.Annotations;
using JetBrains.Core;
using JetBrains.Lifetimes;

// ReSharper disable InconsistentNaming

namespace JetBrains.Collections.Viewable
{
  public static class ReactiveEx
  {
    public static void Fire(this ISignal<Unit> me)
    {
      me.Fire(Unit.Instance);
    }

    public static void Advise(this ISource<Unit> me, Lifetime lifetime, Action handler)
    {
      me.Advise(lifetime, _ => { handler(); });
    }

    public static void AdviseNotNull<T>(this ISource<T> me, Lifetime lifetime, Action<T> handler) where T : class
    {
      me.Advise(lifetime, v => { if  (v != null) handler(v); });
    }
    
    public static void AdviseNotNull<T>(this ISource<T?> me, Lifetime lifetime, Action<T> handler) where T : struct
    {
      me.Advise(lifetime, v => { if (v != null) handler(v.Value); });
    }

    public static void AdviseUntil<T>(this ISource<T> me, Lifetime lifetime, Func<T, bool> handler)
    {
      if (!lifetime.IsAlive) return;

      var definition = Lifetime.Define(lifetime);
      me.Advise(definition.Lifetime, v =>
      {
        if (handler(v)) definition.Terminate();
      });
    }

    public static void AdviseOnce<T>(this ISource<T> me, Lifetime lifetime, Action<T> handler)
    {
      me.AdviseUntil(lifetime, v =>
      {
        handler(v);
        return true;
      });
    }

    public static void View<T>(this IReadonlyProperty<T> me, Lifetime lifetime, Action<Lifetime, T> handler)
    {
      if (!lifetime.IsAlive) return;

      // nested lifetime is needed due to exception that could be thrown
      // while viewing a property change right at the moment of <param>lifetime</param>'s termination
      // but before <param>handler</param> gets removed
      var lf = lifetime == Lifetime.Eternal ? lifetime : Lifetime.Define(lifetime).Lifetime;
      var seq = new SequentialLifetimes(lf);

      me.Advise(lf, v => handler(seq.Next(), v));
    }

    public static void ViewNotNull<T>(this IReadonlyProperty<T> me, Lifetime lifetime, Action<Lifetime, T> handler) where T: class
    {
      me.View(lifetime, (lf, v) =>
      {
        if (v != null) handler(lf, v);
      });
    }
    
    public static void ViewNotNull<T>(this IReadonlyProperty<T?> me, Lifetime lifetime, Action<Lifetime, T> handler) where T: struct
    {
      me.View(lifetime, (lf, v) =>
      {
        if (v != null) handler(lf, v.Value);
      });
    }

    public static void ViewNull<T>(this IReadonlyProperty<T> me, Lifetime lifetime, Action<Lifetime> handler) where T: class
    {
      me.View(lifetime, (lf, v) =>
      {
        if (v == null) handler(lf);
      });
    }
    
    public static void ViewNull<T>(this IReadonlyProperty<T?> me, Lifetime lifetime, Action<Lifetime> handler) where T: struct
    {
      me.View(lifetime, (lf, v) =>
      {
        if (v == null) handler(lf);
      });
    }

    public static void View<K, V>(this IViewableMap<K, V> me, Lifetime lifetime, Action<Lifetime, KeyValuePair<K, V>> handler)
    {
      View(me, lifetime, (lf, k, v) => handler(lf, JetKeyValuePair.Of(k, v)));
    }

    public static void FlowInto<K, V>(this IViewableMap<K, V> me, Lifetime lifetime, IDictionary<K, V> storage)
    {
      me.Advise(lifetime, e =>
      {
        switch (e.Kind)
        {
          case AddUpdateRemove.Add:
          case AddUpdateRemove.Update:
            storage[e.Key] = e.NewValue;
            break;
          case AddUpdateRemove.Remove:
            storage.Remove(e.Key);
            break;
          default:
            throw new ArgumentOutOfRangeException($"Unexpected kind: {e.Kind}");
        }
      });
    }

    [Obsolete("This method has horrible performance when adding 100+ items")]
    public static void AddOrReplaceLifetimed<K, V>(this IViewableMap<K, V> me, Lifetime lifetime, K k, Func<Lifetime, V> vfun)
    {
      var def = lifetime.CreateNested();
      me[k] = vfun(def.Lifetime);
      me.Change.Advise(def.Lifetime, _event =>
      {
        if (_event.Key.Equals(k)) def.Terminate();
      });
    }

    public static void AdviseAddRemove<K, V>(this IViewableMap<K, V> me, Lifetime lifetime, Action<AddRemove, K, V> handler)
    {
      me.Advise(lifetime, e =>
      {
        switch (e.Kind)
        {
          case AddUpdateRemove.Add:
            handler(AddRemove.Add, e.Key, e.NewValue);
            break;
          case AddUpdateRemove.Update:
            handler(AddRemove.Remove, e.Key, e.OldValue);
            handler(AddRemove.Add, e.Key, e.NewValue);
            break;
          case AddUpdateRemove.Remove:
            handler(AddRemove.Remove, e.Key, e.OldValue);
            break;
          default:
            throw new ArgumentOutOfRangeException("" + e.Kind);
        }
      });
    }

    public static void Advise<T>(this IViewableSet<T> me, Lifetime lifetime, Action<AddRemove, T> handler)
    {
      me.Advise(lifetime, e => handler(e.Kind, e.Value));
    }

    public static void View<T>(this IViewableSet<T> me, Lifetime lifetime, Action<Lifetime, T> handler)
    {
      var lifetimes = new Dictionary<T, LifetimeDefinition>();

      me.Advise(lifetime, (kind, value) =>
      {
        switch (kind)
        {
          case AddRemove.Add:
            var def = Lifetime.Define(lifetime);
            lifetimes[value] = def;
            handler(def.Lifetime, value);
            break;

          case AddRemove.Remove:
            def = lifetimes[value];
            lifetimes.Remove(value);
            def.Terminate();
            break;

          default:
            throw new ArgumentOutOfRangeException($"illegal enum value: {kind}");
        }
      });
    }

    public static void View<K, V>(this IViewableMap<K, V> me, Lifetime lifetime, Action<Lifetime, K, V> handler)
    {
      var lifetimes = new Dictionary<KeyValuePair<K, V>, LifetimeDefinition>();

      me.AdviseAddRemove(lifetime, (kind, key, value) =>
      {
        var entry = JetKeyValuePair.Of(key, value);

        switch (kind)
        {
          case AddRemove.Add:
            var def = Lifetime.Define(lifetime);
            lifetimes.Add(entry, def);
            handler(def.Lifetime, entry.Key, entry.Value);
            break;

          case AddRemove.Remove:
            def = lifetimes[entry];
            lifetimes.Remove(entry);
            def.Terminate();
            break;

          default:
            throw new ArgumentOutOfRangeException($"Illegal enum value: {kind}");
        }
      });
    }
    
    
    public static void AdviseAddRemove<V>(this IViewableList<V> me, Lifetime lifetime, Action<AddRemove, int, V> handler)
    {
      me.Advise(lifetime, e =>
      {
        switch (e.Kind)
        {
          case AddUpdateRemove.Add:
            handler(AddRemove.Add, e.Index, e.NewValue);
            break;
          case AddUpdateRemove.Update:
            handler(AddRemove.Remove, e.Index, e.OldValue);
            handler(AddRemove.Add, e.Index, e.NewValue);
            break;
          case AddUpdateRemove.Remove:
            handler(AddRemove.Remove, e.Index, e.OldValue);
            break;
          default:
            throw new ArgumentOutOfRangeException($"Illegal enum value: {e.Kind}");
        }
      });
    }

    
    
    public static void View<V>(this IViewableList<V> me, Lifetime lifetime, Action<Lifetime, int, V> handler)
    {
      var lifetimes = new List<LifetimeDefinition>();

      me.AdviseAddRemove(lifetime, (kind, index, value) =>
      {

        switch (kind)
        {
          case AddRemove.Add:
            var def = Lifetime.Define(lifetime);
            lifetimes.Insert(index, def);
            handler(def.Lifetime, index, value);
            break;

          case AddRemove.Remove:
            def = lifetimes[index];
            lifetimes.RemoveAt(index);
            def.Terminate();
            break;

          default:
            throw new ArgumentOutOfRangeException($"Illegal enum value: {kind}");
        }
      });
    }
    

    public static bool HasValue<T>(this IReadonlyProperty<T> me)
    {
      return me.Maybe.HasValue;
    }

    public static bool HasTrueValue(this IReadonlyProperty<bool> me)
    {
      return me.Maybe.HasValue && me.Maybe.Value;
    }

    public static void Compose<T1, T2>(this IReadonlyProperty<T1> first, Lifetime lifetime, IReadonlyProperty<T2> second, Action<T1, T2> composer)
    {
      first.Advise(lifetime, v =>
      {
        if (second.HasValue()) composer(v, second.Value);
      });
      second.Advise(lifetime, v =>
      {
        if (first.HasValue()) composer(first.Value, v);
      });
    }

    public static IReadonlyProperty<T> Compose<T1, T2, T>(this IReadonlyProperty<T1> first, Lifetime lifetime, IReadonlyProperty<T2> second, Func<T1, T2, T> composer)
    {
      var res = new ViewableProperty<T>();
      first.Advise(lifetime, v =>
      {
        if (second.HasValue()) res.Value = composer(v, second.Value);
      });
      second.Advise(lifetime, v =>
      {
        if (first.HasValue()) res.Value = composer(first.Value, v);
      });
      return res;
    }

    public static void WhenTrue([NotNull] this IReadonlyProperty<bool> property, Lifetime lifetime, [NotNull] Action<Lifetime> handler)
    {
      if (property == null) throw new ArgumentNullException(nameof(property));
      
      if (handler == null) throw new ArgumentNullException(nameof(handler));

      property.View(lifetime, (lf, value) =>
      {
        if (value) handler(lf);
      });
    }

    public static void WhenFalse([NotNull] this IReadonlyProperty<bool> property, Lifetime lifetime, [NotNull] Action<Lifetime> handler)
    {
      if (property == null) throw new ArgumentNullException(nameof(property));
      
      if (handler == null) throw new ArgumentNullException(nameof(handler));

      property.View(lifetime, (lf, value) =>
      {
        if (!value) handler(lf);
      });
    }


    private class MappedSink<T, R> : ISource<R>
    {
      private readonly ISource<T> myOriginal;
      private readonly Func<T, R> myMap;

      public MappedSink(ISource<T> original, Func<T, R> map)
      {
        myOriginal = original;
        myMap = map;
      }

      public void Advise(Lifetime lifetime, Action<R> handler)
      {
        myOriginal.Advise(lifetime, x => handler(myMap(x)));
      }
    }

    private class MappedProperty<T, R> : IReadonlyProperty<R>
    {
      private readonly IViewableProperty<T> mySource;
      private readonly Func<T, R> myMap;

      public MappedProperty(IViewableProperty<T> source, Func<T, R> map)
      {
        mySource = source;
        myMap = map;
        Change = new MappedSink<T, R>(source.Change, myMap);
      }

      public void Advise(Lifetime lifetime, Action<R> handler) => Change.Advise(lifetime, handler);

      public ISource<R> Change { get; }

      public Maybe<R> Maybe => mySource.Maybe.Select(myMap);

      public R Value => Maybe.Value;
    }

    public static IReadonlyProperty<R> Select<T, R>(this IViewableProperty<T> source, Func<T, R> f)
    {
      return new MappedProperty<T,R>(source, f);
    }

  }
}