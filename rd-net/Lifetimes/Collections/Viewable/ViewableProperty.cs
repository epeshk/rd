﻿using System;
using JetBrains.Core;
using JetBrains.Diagnostics;
using JetBrains.Lifetimes;

namespace JetBrains.Collections.Viewable
{
  public class ViewableProperty<T> : IViewableProperty<T>
  {
    private readonly Signal<T> myChange = new Signal<T>();

    public ISource<T> Change => myChange;

    public Maybe<T> Maybe { get; private set; }

    public ViewableProperty() {}

    public ViewableProperty(T value) : this()
    {
      // ReSharper disable once VirtualMemberCallInConstructor
      Value = value;
    }


    public virtual T Value
    {
      get { return Maybe.OrElseThrow(() => new InvalidOperationException("Not initialized")); }

      set
      {
        lock (myChange)
        {
          if (Maybe.HasValue && Equals(Maybe.Value, value)) return;
          Maybe = new Maybe<T>(value);
          myChange.Fire(value);
        }
      }
    }

    public void Advise(Lifetime lifetime, Action<T> handler)
    {
      //todo replace by IsAlive after tests
      if (lifetime.Status >= LifetimeStatus.Terminating) return;

      lock (myChange)
      {
        myChange.Advise(lifetime, handler);
        try
        {
          if (Maybe.HasValue) handler(Value);
        }
        catch (Exception e)
        {
          Log.Root.Error(e);
        }
      }
    }
  }
}