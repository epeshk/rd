﻿using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using JetBrains.Annotations;
using JetBrains.Lifetimes;

namespace JetBrains.Collections.Viewable
{
    public class ViewableSet<T> : IViewableSet<T>
    {
        private readonly Signal<SetEvent<T>> myChange = new Signal<SetEvent<T>>();
#if !NET35
    private readonly ISet<T> myStorage;
#else
        private readonly HashSet<T> myStorage;
#endif
    
        public ISource<SetEvent<T>> Change => myChange;


        [PublicAPI] public ViewableSet() : this (new HashSet<T>()) {}
        [PublicAPI] public ViewableSet([NotNull]
#if !NET35
            ISet<T> storage
#else
            HashSet<T> storage
#endif
        )
        {
            myStorage = storage ?? throw new ArgumentNullException(nameof(storage));
        }
        
        
        public void Advise(Lifetime lifetime, Action<SetEvent<T>> handler)
        {
            foreach (var elt in myStorage) 
                handler(SetEvent<T>.Add(elt));
      
            myChange.Advise(lifetime, handler);
        }
        

        #region ICollection delegation
        public IEnumerator<T> GetEnumerator() => myStorage.GetEnumerator();
        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
        
        // ReSharper disable once AssignNullToNotNullAttribute
        void ICollection<T>.Add(T item) => Add(item);
        
        public bool Contains(T item) => myStorage.Contains(item);
        public int Count => myStorage.Count;

        public bool IsReadOnly =>
#if !NET35
                myStorage.IsReadOnly;
#else
            false;
#endif
        
        public void CopyTo(T[] array, int arrayIndex) => myStorage.CopyTo(array, arrayIndex);
        
        #endregion


        #region ISet Read Methods
        
        public bool IsProperSubsetOf(IEnumerable<T> other) => myStorage.IsProperSubsetOf(other);
        public bool IsProperSupersetOf(IEnumerable<T> other) => myStorage.IsProperSupersetOf(other);
        public bool IsSubsetOf(IEnumerable<T> other) => myStorage.IsSubsetOf(other);
        public bool IsSupersetOf(IEnumerable<T> other) => myStorage.IsSupersetOf(other);
        public bool Overlaps(IEnumerable<T> other) => myStorage.Overlaps(other);
        public bool SetEquals(IEnumerable<T> other) => myStorage.SetEquals(other);
        
        #endregion

        
        #region Simple write methods
        public bool Add(T item)
        {
            if (!myStorage.Add(item))
                return false;
      
            myChange.Fire(SetEvent<T>.Add(item));
            return true;
        }
        
        

        public bool Remove(T item)
        {
            if (!myStorage.Remove(item)) return false;

            myChange.Fire(SetEvent<T>.Remove(item));
            return true;
        }
        
        
        #endregion
        
        
        #region Bulk write methods

        private void BulkFire(AddRemove kind, IEnumerable<T> values)
        {
            if (kind == AddRemove.Add)
                foreach (var change in values) myChange.Fire(SetEvent<T>.Add(change));
            else
                foreach (var change in values) myChange.Fire(SetEvent<T>.Remove(change));
        }
        
        public void Clear()
        {
            var removed = myStorage.ToArray();
            myStorage.Clear();

            BulkFire(AddRemove.Remove, removed);
        }
        
        
        public void ExceptWith(IEnumerable<T> other)
        {
            var removed = new HashSet<T>(myStorage);
            myStorage.ExceptWith(other);
            
            removed.ExceptWith(myStorage);
            BulkFire(AddRemove.Remove, removed);
        }

        
        public void IntersectWith(IEnumerable<T> other)
        {
            var removed = new HashSet<T>(myStorage);
            myStorage.IntersectWith(other);
            
            removed.ExceptWith(myStorage);
            BulkFire(AddRemove.Remove, removed);
        }


        public void UnionWith(IEnumerable<T> other)
        {
            var added = new HashSet<T>(other);
            added.ExceptWith(myStorage);
            myStorage.UnionWith(added);
            
            BulkFire(AddRemove.Add, added);
        }
        
        
        public void SymmetricExceptWith(IEnumerable<T> other)
        {
            // ReSharper disable once PossibleMultipleEnumeration
            var otherExceptThis = new HashSet<T>(other);
            otherExceptThis.ExceptWith(this);
            
            // ReSharper disable once PossibleMultipleEnumeration
            ExceptWith(other); //will throw remove events for intersection
            
            UnionWith(otherExceptThis); //will throw add events for union
        }

        
        #endregion
    }
}